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
  private final org.adancau.doneapi.household.HouseholdService households;

  public ActivityService(
      ActivityRepository events,
      MutationService mutations,
      DomainViews views,
      Clock clock,
      org.adancau.doneapi.household.HouseholdService households) {
    this.events = events;
    this.mutations = mutations;
    this.views = views;
    this.clock = clock;
    this.households = households;
  }

  @Transactional(readOnly = true)
  public PageResponse<ActivityDtos.ActivityView> list(UUID owner, Pageable page, UUID householdId) {
    if (householdId != null) households.owned(owner, householdId);
    return PageResponse.from(
        (householdId == null
                ? events.findByUserIdOrderByOccurredAtDescIdDesc(owner, page)
                : events.findByHousehold(owner, householdId, page))
            .map(views::activity));
  }

  public ActivityDtos.ActivityView read(UUID owner, UUID id, UUID key) {
    return mutations.execute(
        owner,
        key,
        "activity.read:" + id,
        Map.of(),
        ActivityDtos.ActivityView.class,
        u -> {
          var e = events.findByIdAndUserId(id, owner).orElseThrow(ApiException::missing);
          if (e.getReadAt() == null) e.setReadAt(clock.instant());
          return views.activity(e);
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
          if (householdId == null) events.markAllRead(owner, clock.instant());
          else {
            households.owned(owner, householdId);
            events.markHouseholdRead(owner, householdId, clock.instant());
          }
          return new MutationResult(u.getRevision());
        });
  }
}
