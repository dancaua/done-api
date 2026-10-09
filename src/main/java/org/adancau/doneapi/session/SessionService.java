package org.adancau.doneapi.session;

import java.time.*;
import java.util.*;
import org.adancau.doneapi.appliance.*;
import org.adancau.doneapi.common.*;
import org.adancau.doneapi.persistence.*;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {
  private final SessionRepository sessions;
  private final ProgramRepository programs;
  private final ApplianceService appliances;
  private final MutationService mutations;
  private final DomainViews views;
  private final SessionEvents events;
  private final Clock clock;

  public SessionService(
      SessionRepository sessions,
      ProgramRepository programs,
      ApplianceService appliances,
      MutationService mutations,
      DomainViews views,
      SessionEvents events,
      Clock clock) {
    this.sessions = sessions;
    this.programs = programs;
    this.appliances = appliances;
    this.mutations = mutations;
    this.views = views;
    this.events = events;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public SessionDtos.SessionView get(UUID owner, UUID id) {
    return views.session(owned(owner, id));
  }

  private SessionEntity owned(UUID owner, UUID id) {
    return sessions.findByIdAndUserId(id, owner).orElseThrow(ApiException::missing);
  }

  @Transactional(readOnly = true)
  public PageResponse<SessionDtos.SessionView> history(
      UUID owner, UUID applianceId, Pageable page) {
    appliances.owned(owner, applianceId);
    return PageResponse.from(
        sessions
            .findByUserIdAndApplianceIdOrderByStartedAtDescIdDesc(owner, applianceId, page)
            .map(views::session));
  }

  public SessionDtos.SessionView start(UUID owner, UUID applianceId, UUID key, SessionDtos.StartSession request) {
    return mutations.execute(owner, key, "session.start:" + applianceId, request,
        SessionDtos.SessionView.class, user -> startNew(owner, applianceId, request, null));
  }

  private SessionDtos.SessionView startNew(UUID owner, UUID applianceId, SessionDtos.StartSession request,
      ApplianceDtos.ProgramSnapshot preserved) {
    var a = appliances.owned(owner, applianceId);
    if (sessions.findByApplianceIdAndCollectedAtIsNullAndCanceledAtIsNull(applianceId).isPresent())
      throw ApiException.conflict("session_exists", "Close the active session first.");
    TimerMode mode = request.mode() == null ? TimerMode.countdown : request.mode();
    var s = new SessionEntity();
    s.setUserId(owner); s.setApplianceId(applianceId); s.setMode(mode); s.setStartedAt(clock.instant());
    if (mode == TimerMode.stopwatch) {
      if (request.programId() != null || request.minutes() != null
          || (request.program() != null && request.program().minutes() != 0)
          || Boolean.TRUE.equals(request.saveProgram()))
        throw ApiException.invalid("Stopwatch has no preset duration or saved program at start.");
      String name = Names.clean(request.program() == null ? null : request.program().name(), 60, false);
      s.setProgramName(name.isEmpty() ? "Sesiune" : name);
      s.setProgramLocalizationKey(name.isEmpty() ? "timer.session" : null);
      s.setProgramCustom(!name.isEmpty()); s.setMinutes(0);
    } else {
      if ((request.programId() == null) == (request.program() == null))
        throw ApiException.invalid("Choose a saved program or a custom timer.");
      ProgramEntity preset = null;
      if (request.programId() != null) {
        preset = programs.findByIdAndApplianceIdAndUserId(request.programId(), applianceId, owner)
            .orElseThrow(ApiException::missing);
        s.setSourceProgramId(preset.getId()); s.setProgramId(preset.getId());
        s.setProgramName(preset.getName());
        s.setMinutes(request.minutes() == null ? preset.getMinutes() : request.minutes());
        s.setProgramLocalizationKey(preset.getLocalizationKey()); s.setProgramCustom(preset.isCustom());
      } else {
        if (request.minutes() != null) throw ApiException.invalid("Use program.minutes for a custom timer.");
        int minutes = request.program().minutes();
        if (minutes < 1) throw ApiException.invalid("Countdown duration must be positive.");
        String name = Names.clean(request.program().name(), 60, false);
        s.setProgramName(name.isEmpty() ? "Timer" : name); s.setMinutes(minutes);
        s.setProgramLocalizationKey(name.isEmpty() ? "Timer" : null); s.setProgramCustom(!name.isEmpty());
      }
      if (preserved != null) {
        s.setProgramLocalizationKey(preserved.localizationKey()); s.setProgramCustom(preserved.isCustom());
      }
      if (!Boolean.FALSE.equals(request.saveProgram()) &&
          (preset == null || preset.getMinutes() != s.getMinutes())) {
        var p = appliances.saveProgramValue(owner, applianceId, s.getProgramName(), s.getMinutes(),
            s.getProgramLocalizationKey(), s.isProgramCustom());
        s.setProgramId(p.getId());
      }
      s.setExpectedEnd(s.getStartedAt().plusSeconds(s.getMinutes() * 60L));
    }
    events.reschedule(s); sessions.saveAndFlush(s);
    events.event(s, a, "started", "Timer pornit.", a.getName() + ": " + s.getProgramName(), s.getStartedAt());
    return views.session(s);
  }

  public SessionDtos.SessionView measured(UUID owner, UUID id, UUID key, SessionDtos.MeasuredProgram request) {
    return mutations.execute(owner, key, "session.measured-program:" + id, request,
        SessionDtos.SessionView.class, user -> {
          var s = owned(owner, id);
          String name = Names.clean(request.name(), 60, true);
          if (s.getMode() != TimerMode.stopwatch || s.getCompletedAt() == null || s.getCanceledAt() != null)
            throw ApiException.conflict("measurement_unavailable", "Confirm a stopwatch session first.");
          var duration = Duration.between(s.getStartedAt(), s.getCompletedAt());
          if (Duration.between(s.getStartedAt(), s.getCompletedAt()).compareTo(Duration.ofDays(1)) > 0)
            throw ApiException.conflict("measurement_too_long", "Only measurements of up to 24 hours can become a program.");
          int minutes = (int)Math.max(1, (duration.toNanos() + 59_999_999_999L) / 60_000_000_000L);
          if (s.getMeasuredProgramName() != null) {
            if (!s.getMeasuredProgramName().equals(name))
              throw ApiException.conflict("measurement_already_saved", "This measurement already has a saved name.");
            return views.session(s); // A retry never resurrects a deleted preset.
          }
          appliances.saveProgramValue(owner, s.getApplianceId(), name, minutes, null, true);
          s.setMeasuredProgramName(name); s.setMeasuredProgramMinutes(minutes); sessions.flush();
          return views.session(s);
        });
  }

  public SessionDtos.SessionView repeat(UUID owner, UUID id, UUID key) {
    return mutations.execute(owner, key, "session.repeat:" + id, Map.of(), SessionDtos.SessionView.class, user -> {
      var source = owned(owner, id);
      var value = source.getMeasuredProgramName() == null ? views.programSnapshot(source) : views.measuredSnapshot(source);
      var mode = value.minutes() == 0 ? TimerMode.stopwatch : TimerMode.countdown;
      return startNew(owner, source.getApplianceId(), new SessionDtos.StartSession(null,
          new ApplianceDtos.ProgramInput(value.name(), value.minutes()), mode, mode == TimerMode.countdown, null), value);
    });
  }

  public SessionDtos.SessionView action(
      UUID owner, UUID id, UUID key, String action, SessionDtos.ExtendSession extension) {
    Object payload = extension == null ? Map.of() : extension;
    return mutations.execute(
        owner,
        key,
        "session." + action + ":" + id,
        payload,
        SessionDtos.SessionView.class,
        user -> {
          var s = owned(owner, id);
          var a = appliances.owned(owner, s.getApplianceId());
          Instant now =
              clock.instant().isBefore(s.getStartedAt()) ? s.getStartedAt() : clock.instant();
          switch (action) {
            case "complete" -> {
              if (s.getCanceledAt() != null)
                throw ApiException.conflict("session_closed", "Sesiunea a fost oprită.");
              if (s.getCompletedAt() == null) {
                s.setCompletedAt(now);
                if (!a.getKind().needsCollection()) s.setCollectedAt(now);
                events.event(
                    s,
                    a,
                    "finished",
                    a.getKind().needsCollection() ? "Program încheiat!" : "Sesiune încheiată!",
                    a.getName() + ": finalizare confirmată.",
                    now);
              }
            }
            case "collect" -> {
              if (s.getCompletedAt() == null)
                throw ApiException.conflict(
                    "not_completed", "Confirmă finalizarea înainte de eliberarea aparatului.");
              if (s.getCollectedAt() == null) s.setCollectedAt(now);
            }
            case "cancel" -> {
              if (s.getCompletedAt() != null)
                throw ApiException.conflict("already_completed", "Sesiunea este deja încheiată.");
              if (s.getCanceledAt() == null) {
                s.setCanceledAt(now);
                events.event(
                    s,
                    a,
                    "canceled",
                    "Sesiune oprită",
                    a.getName() + ": sesiunea a fost oprită manual.",
                    now);
              }
            }
            case "extend" -> {
              if (!s.isOpen() || s.getCompletedAt() != null)
                throw ApiException.conflict("session_closed", "Sesiunea nu mai este activă.");
              if (s.getExpectedEnd() == null)
                throw ApiException.invalid("Cronometrul nu are un final estimat.");
              Instant end =
                  (s.getExpectedEnd().isAfter(now) ? s.getExpectedEnd() : now)
                      .plusSeconds(extension.minutes() * 60L);
              if (end.isAfter(s.getStartedAt().plusSeconds(604800)))
                throw ApiException.invalid("O sesiune countdown poate dura maximum șapte zile.");
              s.setExpectedEnd(end);
              s.setDueSent(false);
              s.setFiveSent(false);
            }
            default -> throw ApiException.invalid("Acțiune necunoscută.");
          }
          events.reschedule(s);
          sessions.flush();
          return views.session(s);
        });
  }
}
