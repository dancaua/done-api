package org.adancau.doneapi.session;

import java.time.*;
import java.util.List;
import org.adancau.doneapi.activity.ActivityDtos.ActivityView;
import org.adancau.doneapi.appliance.ApplianceDtos.*;
import org.adancau.doneapi.persistence.*;
import org.springframework.stereotype.Component;

@Component
public class DomainViews {
  private final Clock clock;
  private final UserRepository users;
  private final org.adancau.doneapi.common.MessageCatalog catalog;
  private final tools.jackson.databind.json.JsonMapper json;

  public DomainViews(Clock clock, UserRepository users, org.adancau.doneapi.common.MessageCatalog catalog, tools.jackson.databind.json.JsonMapper json) {
    this.clock = clock; this.users=users; this.catalog=catalog; this.json=json;
  }

  public String status(SessionEntity s) {
    if (s.getCanceledAt() != null) return "canceled";
    if (s.getCollectedAt() != null) return "collected";
    if (s.getCompletedAt() != null) return "done";
    return s.getExpectedEnd() != null && !s.getExpectedEnd().isAfter(clock.instant())
        ? "due"
        : "running";
  }

  public SessionDtos.SessionView session(SessionEntity s) {
    Instant end =
        s.getCompletedAt() != null
            ? s.getCompletedAt()
            : s.getCanceledAt() != null ? s.getCanceledAt() : clock.instant();
    return new SessionDtos.SessionView(
        s.getId(),
        s.getApplianceId(),
        s.getProgramId(),
        s.getProgramName(),
        s.getMinutes(),
        s.getMode(),
        status(s),
        s.getStartedAt(),
        s.getExpectedEnd(),
        s.getCompletedAt(),
        s.getCollectedAt(),
        s.getCanceledAt(),
        Math.max(0, Duration.between(s.getStartedAt(), end).toSeconds()),
        s.getVersion(),
        clock.instant(), s.getSourceProgramId(), programSnapshot(s), measuredSnapshot(s), s.getMode(), s.isOpen());
  }

  public ProgramSnapshot programSnapshot(SessionEntity s) {
    return new ProgramSnapshot(s.getProgramName(), s.getMinutes(), s.getProgramLocalizationKey(), s.isProgramCustom());
  }
  public ProgramSnapshot measuredSnapshot(SessionEntity s) {
    return s.getMeasuredProgramName() == null ? null : new ProgramSnapshot(s.getMeasuredProgramName(), s.getMeasuredProgramMinutes(), null, true);
  }
  public ProgramView program(ProgramEntity p) {
    return new ProgramView(p.getId(), p.getName(), p.getMinutes(), p.isSuggested(), p.getVersion(),
        p.getApplianceId(), p.getLocalizationKey(), p.isCustom(), p.getPosition());
  }

  public ApplianceView appliance(
      ApplianceEntity a, List<ProgramEntity> programs, SessionEntity active) {
    return new ApplianceView(
        a.getId(),
        a.getHouseholdId(),
        a.getName(),
        a.getKind(),
        active == null ? "idle" : status(active),
        a.isNotificationsEnabled(),
        programs.stream().map(this::program).toList(),
        active == null ? null : session(active),
        a.getCreatedAt(),
        a.getVersion());
  }

  public ActivityView activity(ActivityEntity e) {
    String language=users.findById(e.getUserId()).map(UserEntity::getLanguage).orElse("en");
    java.util.List<String> arguments=e.getMessageArguments()==null ? java.util.List.of() :
        java.util.Arrays.asList(json.readValue(e.getMessageArguments(),String[].class));
    var program=e.getProgramSnapshot()==null ? null : json.readValue(e.getProgramSnapshot(),ProgramSnapshot.class);
    if (program!=null && program.localizationKey()!=null && !arguments.isEmpty()) {
      arguments=new java.util.ArrayList<>(arguments);arguments.set(0,catalog.text(language,program.localizationKey(),java.util.List.of()));
    }
    var message=e.getMessageKey()==null ? null : new org.adancau.doneapi.activity.ActivityDtos.LocalizedMessage(e.getMessageKey(),arguments);
    return new ActivityView(e.getId(),e.getApplianceId(),e.getSessionId(),e.getKind(),
        catalog.text(language,e.getTitleKey()==null ? e.getTitle() : e.getTitleKey(),java.util.List.of()),
        message==null ? e.getDetail() : catalog.text(language,message.key(),message.arguments()),
        e.getOccurredAt(),e.getReadAt(),e.getTitleKey(),message,program);
  }
}
