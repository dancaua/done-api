package org.adancau.doneapi.sync;

import java.time.Instant;
import java.util.List;
import org.adancau.doneapi.account.AccountDtos.UserView;
import org.adancau.doneapi.activity.ActivityDtos.ActivityView;
import org.adancau.doneapi.appliance.ApplianceDtos.ApplianceView;
import org.adancau.doneapi.session.SessionDtos.SessionView;

public final class StateDtos {
  private StateDtos() {}

  public record Snapshot(
      long revision,
      Instant serverTime,
      UserView user,
      List<org.adancau.doneapi.household.HouseholdDtos.HouseholdView> households,
      List<ApplianceView> appliances,
      List<SessionView> recentSessions,
      boolean hasMoreSessions,
      List<ActivityView> recentActivity,
      long unreadActivityCount) {}

  public record Export(
      int schemaVersion,
      Instant exportedAt,
      UserView user,
      List<org.adancau.doneapi.household.HouseholdDtos.HouseholdView> households,
      List<ApplianceView> appliances,
      List<SessionView> sessions,
      List<ActivityView> activity) {}
}
