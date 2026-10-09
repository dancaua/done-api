package org.adancau.doneapi.sync;

import java.time.Clock;
import java.util.UUID;
import org.adancau.doneapi.account.UserViews;
import org.adancau.doneapi.appliance.ApplianceService;
import org.adancau.doneapi.common.ApiException;
import org.adancau.doneapi.persistence.*;
import org.adancau.doneapi.session.DomainViews;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class StateService {
  private final UserRepository users;
  private final ApplianceService appliances;
  private final SessionRepository sessions;
  private final ActivityRepository activity;
  private final DomainViews views;
  private final UserViews userViews;
  private final Clock clock;
  private final org.adancau.doneapi.household.HouseholdService households;

  public StateService(
      UserRepository users,
      ApplianceService appliances,
      SessionRepository sessions,
      ActivityRepository activity,
      DomainViews views,
      UserViews userViews,
      Clock clock,
      org.adancau.doneapi.household.HouseholdService households) {
    this.users = users;
    this.appliances = appliances;
    this.sessions = sessions;
    this.activity = activity;
    this.views = views;
    this.userViews = userViews;
    this.clock = clock;
    this.households = households;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public StateDtos.Snapshot snapshot(UUID owner) {
    var user = users.findById(owner).orElseThrow(ApiException::unauthorized);
    var recent = sessions.findByUserIdOrderByStartedAtDescIdDesc(owner, PageRequest.of(0, 101));
    return new StateDtos.Snapshot(
        user.getRevision(),
        clock.instant(),
        userViews.view(user),
        households.list(owner),
        appliances.list(owner),
        recent.stream().limit(100).map(views::session).toList(),
        recent.size() > 100,
        activity
            .findByUserIdOrderByOccurredAtDescIdDesc(owner, PageRequest.of(0, 100))
            .getContent()
            .stream()
            .map(views::activity)
            .toList(),
        activity.countByUserIdAndReadAtIsNull(owner));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public StateDtos.Export export(UUID owner) {
    var user = users.findById(owner).orElseThrow(ApiException::unauthorized);
    return new StateDtos.Export(
        1,
        clock.instant(),
        userViews.view(user),
        households.list(owner),
        appliances.list(owner),
        sessions.findByUserIdOrderByStartedAtDescIdDesc(owner, Pageable.unpaged()).stream()
            .map(views::session)
            .toList(),
        activity
            .findByUserIdOrderByOccurredAtDescIdDesc(owner, Pageable.unpaged())
            .getContent()
            .stream()
            .map(views::activity)
            .toList());
  }
}
