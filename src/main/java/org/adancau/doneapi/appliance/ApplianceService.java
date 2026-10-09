package org.adancau.doneapi.appliance;

import java.time.Clock;
import java.util.*;
import org.adancau.doneapi.account.AccountDtos.MutationResult;
import org.adancau.doneapi.common.*;
import org.adancau.doneapi.persistence.*;
import org.adancau.doneapi.session.DomainViews;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplianceService {
  private final ApplianceRepository appliances;
  private final ProgramRepository programs;
  private final SessionRepository sessions;
  private final MutationService mutations;
  private final DomainViews views;
  private final Clock clock;
  private final org.adancau.doneapi.household.HouseholdService households;

  public ApplianceService(
      ApplianceRepository appliances,
      ProgramRepository programs,
      SessionRepository sessions,
      MutationService mutations,
      DomainViews views,
      Clock clock,
      org.adancau.doneapi.household.HouseholdService households) {
    this.appliances = appliances;
    this.programs = programs;
    this.sessions = sessions;
    this.mutations = mutations;
    this.views = views;
    this.clock = clock;
    this.households = households;
  }

  public ApplianceEntity owned(UUID owner, UUID id) {
    return appliances.findByIdAndUserId(id, owner).orElseThrow(ApiException::missing);
  }

  private ApplianceDtos.ApplianceView view(ApplianceEntity a) {
    return views.appliance(
        a,
        programs.findByApplianceIdAndUserIdOrderByPositionAscIdAsc(a.getId(), a.getUserId()),
        sessions.findByApplianceIdAndCollectedAtIsNullAndCanceledAtIsNull(a.getId()).orElse(null));
  }

  @Transactional(readOnly = true)
  public List<ApplianceDtos.ApplianceView> list(UUID owner) {
    return appliances.findByUserIdOrderByCreatedAtAscIdAsc(owner).stream().map(this::view).toList();
  }

  @Transactional(readOnly = true)
  public ApplianceDtos.ApplianceView get(UUID owner, UUID id) {
    return view(owned(owner, id));
  }

  @Transactional(readOnly = true)
  public List<ApplianceDtos.ApplianceView> list(UUID owner, UUID householdId) {
    if (householdId == null) return list(owner);
    households.owned(owner, householdId);
    return appliances
        .findByUserIdAndHouseholdIdOrderByCreatedAtAscIdAsc(owner, householdId)
        .stream()
        .map(this::view)
        .toList();
  }

  public ApplianceDtos.ApplianceView move(
      UUID owner, UUID id, UUID key, ApplianceDtos.MoveAppliance r) {
    return mutations.execute(
        owner,
        key,
        "appliance.move:" + id,
        r,
        ApplianceDtos.ApplianceView.class,
        u -> {
          var a = owned(owner, id);
          households.owned(owner, r.householdId());
          if (a.getVersion() != r.version())
            throw ApiException.conflict("stale_version", "Reload the appliance before moving it.");
          if (!a.getHouseholdId().equals(r.householdId())
              && appliances.existsByUserIdAndHouseholdIdAndNameIgnoreCase(
                  owner, r.householdId(), a.getName()))
            throw ApiException.conflict(
                "duplicate_name",
                "An appliance with this name already exists in the destination household.");
          a.setHouseholdId(r.householdId());
          appliances.flush();
          return view(a);
        });
  }

  public ApplianceDtos.ApplianceView notificationSettings(
      UUID owner, UUID id, UUID key, ApplianceDtos.ApplianceNotificationSettings r) {
    return mutations.execute(
        owner,
        key,
        "appliance.notifications:" + id,
        r,
        ApplianceDtos.ApplianceView.class,
        u -> {
          var a = owned(owner, id);
          if (a.getVersion() != r.version())
            throw ApiException.conflict(
                "stale_version", "Reload the appliance before changing notifications.");
          a.setNotificationsEnabled(r.notificationsEnabled());
          appliances.flush();
          return view(a);
        });
  }

  public ApplianceDtos.ApplianceView create(UUID owner, UUID key, ApplianceDtos.CreateAppliance r) {
    return mutations.execute(
        owner,
        key,
        "appliance.create",
        r,
        ApplianceDtos.ApplianceView.class,
        u -> {
          if (appliances.countByUserId(owner) >= 50)
            throw ApiException.conflict("appliance_limit", "Poți adăuga maximum 50 de aparate.");
          var home =
              r.householdId() == null
                  ? households.defaultHome(owner)
                  : households.owned(owner, r.householdId());
          String name = Names.clean(r.name(), 40, r.kind() == ApplianceKind.custom);
          if (name.isEmpty()) name = r.kind().defaultName;
          if (appliances.existsByUserIdAndHouseholdIdAndNameIgnoreCase(owner, home.getId(), name))
            throw ApiException.conflict("duplicate_name", "Există deja un aparat cu acest nume.");
          var a = new ApplianceEntity();
          a.setUserId(owner);
          a.setHouseholdId(home.getId());
          a.setName(name);
          a.setKind(r.kind());
          a.setNotificationsEnabled(r.notificationsEnabled() == null || r.notificationsEnabled());
          a.setCreatedAt(clock.instant());
          appliances.saveAndFlush(a);
          int position = 0;
          for (var suggestion : r.kind().suggestions()) {
            var p = new ProgramEntity();
            p.setUserId(owner);
            p.setApplianceId(a.getId());
            p.setName(suggestion.name());
            p.setMinutes(suggestion.minutes());
            p.setSuggested(true);
            p.setLocalizationKey(suggestion.localizationKey());
            p.setCustom(false);
            p.setPosition(position++);
            programs.save(p);
          }
          programs.flush();
          return view(a);
        });
  }

  public ApplianceDtos.ApplianceView rename(
      UUID owner, UUID id, UUID key, ApplianceDtos.RenameAppliance r) {
    return mutations.execute(
        owner,
        key,
        "appliance.rename:" + id,
        r,
        ApplianceDtos.ApplianceView.class,
        u -> {
          var a = owned(owner, id);
          if (a.getVersion() != r.version())
            throw ApiException.conflict(
                "stale_version", "Aparatul s-a modificat. Reîncarcă datele.");
          String name = Names.clean(r.name(), 40, true);
          if (!name.equalsIgnoreCase(a.getName())
              && appliances.existsByUserIdAndHouseholdIdAndNameIgnoreCase(
                  owner, a.getHouseholdId(), name))
            throw ApiException.conflict("duplicate_name", "Există deja un aparat cu acest nume.");
          a.setName(name);
          appliances.flush();
          return view(a);
        });
  }

  public MutationResult delete(UUID owner, UUID id, UUID key) {
    return mutations.execute(
        owner,
        key,
        "appliance.delete:" + id,
        Map.of(),
        MutationResult.class,
        u -> {
          appliances.delete(owned(owner, id));
          appliances.flush();
          return new MutationResult(u.getRevision());
        });
  }

  public ApplianceDtos.ProgramView createProgram(
      UUID owner, UUID applianceId, UUID key, ApplianceDtos.ProgramInput r) {
    return mutations.execute(
        owner,
        key,
        "program.create:" + applianceId,
        r,
        ApplianceDtos.ProgramView.class,
        u -> {
          owned(owner, applianceId);
          return views.program(saveProgram(owner, applianceId, r, false));
        });
  }

  public ProgramEntity saveProgram(UUID owner, UUID applianceId, ApplianceDtos.ProgramInput r, boolean reuse) {
    String name = Names.clean(r.name(), 60, true);
    if (!reuse && programs.findByApplianceIdAndNameAndMinutes(applianceId, name, r.minutes()).stream().anyMatch(ProgramEntity::isCustom))
      throw ApiException.conflict("duplicate_program", "This custom program already exists.");
    return saveProgramValue(owner, applianceId, name, r.minutes(), null, true);
  }

  public ProgramEntity saveProgramValue(UUID owner, UUID applianceId, String value, int minutes,
      String localizationKey, boolean custom) {
    if (minutes < 1 || minutes > 1440) throw ApiException.invalid("Saved program duration must be 1–1440 minutes.");
    String name = Names.clean(value, 60, true);
    var existing = programs.findByApplianceIdAndNameAndMinutes(applianceId, name, minutes).stream()
        .filter(p -> p.isCustom() == custom && Objects.equals(p.getLocalizationKey(), localizationKey)).findFirst();
    if (existing.isPresent()) return existing.get();
    long count = programs.countByApplianceId(applianceId);
    if (count >= 30) throw ApiException.conflict("program_limit", "Maximum 30 programs per appliance.");
    var p = new ProgramEntity(); p.setUserId(owner); p.setApplianceId(applianceId);
    p.setName(name); p.setMinutes(minutes); p.setLocalizationKey(localizationKey);
    p.setCustom(custom); p.setSuggested(!custom); p.setPosition((int)count);
    return programs.saveAndFlush(p);
  }

  public ApplianceDtos.ProgramView updateProgram(
      UUID owner, UUID applianceId, UUID id, UUID key, ApplianceDtos.UpdateProgram r) {
    return mutations.execute(
        owner,
        key,
        "program.update:" + applianceId + ":" + id,
        r,
        ApplianceDtos.ProgramView.class,
        u -> {
          var p =
              programs
                  .findByIdAndApplianceIdAndUserId(id, applianceId, owner)
                  .orElseThrow(ApiException::missing);
          if (p.getVersion() != r.version())
            throw ApiException.conflict(
                "stale_version", "Programul s-a modificat. Reîncarcă datele.");
          var other = programs.findByApplianceIdAndNameAndMinutes(applianceId, Names.clean(r.name(), 60, true), r.minutes());
          if (other.stream().anyMatch(o -> !o.getId().equals(id) && o.isCustom()))
            throw ApiException.conflict("duplicate_program", "This custom program already exists.");
          p.setName(Names.clean(r.name(), 60, true));
          p.setMinutes(r.minutes());
          p.setSuggested(false);
          p.setLocalizationKey(null);
          p.setCustom(true);
          programs.flush();
          return views.program(p);
        });
  }

  public MutationResult deleteProgram(UUID owner, UUID applianceId, UUID id, UUID key) {
    return mutations.execute(
        owner,
        key,
        "program.delete:" + applianceId + ":" + id,
        Map.of(),
        MutationResult.class,
        u -> {
          programs.delete(
              programs
                  .findByIdAndApplianceIdAndUserId(id, applianceId, owner)
                  .orElseThrow(ApiException::missing));
          programs.flush();
          return new MutationResult(u.getRevision());
        });
  }
}
