package org.adancau.doneapi.activity;

import java.time.Clock;
import java.util.*;
import org.adancau.doneapi.account.AccountDtos.MutationResult;
import org.adancau.doneapi.common.*;
import org.adancau.doneapi.persistence.*;
import org.adancau.doneapi.session.DomainViews;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityService {
  private final ActivityRepository events;
  private final MutationService mutations;
  private final DomainViews views;
  private final Clock clock;
  private final org.adancau.doneapi.household.HouseholdAccess access;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;
  private final org.adancau.doneapi.household.HouseholdService households;

  public ActivityService(
      ActivityRepository events,
      MutationService mutations,
      DomainViews views,
      Clock clock,
      org.adancau.doneapi.household.HouseholdAccess access,
      org.springframework.jdbc.core.JdbcTemplate jdbc,
      org.adancau.doneapi.household.HouseholdService households) {
    this.events = events;
    this.mutations = mutations;
    this.views = views;
    this.clock = clock;
    this.access = access;
    this.jdbc = jdbc;
    this.households = households;
  }

  @Transactional(readOnly = true)
  public PageResponse<ActivityDtos.ActivityView> list(UUID owner, Pageable page, UUID householdId) {
    if (householdId != null) access.home(owner, householdId);
    return PageResponse.from(
        events
            .visible(householdId == null ? access.homes(owner) : List.of(householdId), page)
            .map(e -> views.activity(e, owner)));
  }

  public ActivityDtos.ActivityView read(UUID actor, UUID id, UUID key) {
    var event = events.findById(id).orElseThrow(ApiException::missing);
    var appliance = access.appliance(actor, event.getApplianceId());
    return mutations.executeShared(
        actor,
        appliance.getHouseholdId(),
        key,
        "activity.read:" + id,
        Map.of(),
        ActivityDtos.ActivityView.class,
        u -> {
          if (!access
              .appliance(actor, event.getApplianceId())
              .getHouseholdId()
              .equals(appliance.getHouseholdId())) throw ApiException.missing();
          jdbc.update(
              "INSERT IGNORE INTO activity_reads(event_id,user_id,read_at) VALUES (?,?,?)",
              id.toString(),
              actor.toString(),
              java.sql.Timestamp.from(clock.instant()));
          return views.activity(event, actor);
        });
  }

  public MutationResult readAll(UUID owner, UUID key, UUID householdId) {
    return mutations.execute(
        owner,
        key,
        householdId == null ? "activity.readAll" : "activity.readAll:" + householdId,
        Map.of(),
        MutationResult.class,
        u -> {
          if (householdId != null) access.home(owner, householdId);
          for (var e :
              events.visible(
                  householdId == null ? access.homes(owner) : List.of(householdId),
                  Pageable.unpaged()))
            jdbc.update(
                "INSERT IGNORE INTO activity_reads(event_id,user_id,read_at) VALUES (?,?,?)",
                e.getId().toString(),
                owner.toString(),
                java.sql.Timestamp.from(clock.instant()));
          return new MutationResult(u.getRevision());
        });
  }
}
