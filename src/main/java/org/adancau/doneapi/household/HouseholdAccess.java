package org.adancau.doneapi.household;

import java.util.*;
import org.adancau.doneapi.common.ApiException;
import org.adancau.doneapi.persistence.*;
import org.springframework.stereotype.Service;

@Service
public class HouseholdAccess {
  private final HouseholdRepository homes;
  private final HouseholdMemberRepository members;
  private final ApplianceRepository appliances;
  private final SessionRepository sessions;

  public HouseholdAccess(
      HouseholdRepository h,
      HouseholdMemberRepository m,
      ApplianceRepository a,
      SessionRepository s) {
    homes = h;
    members = m;
    appliances = a;
    sessions = s;
  }

  public HouseholdEntity home(UUID actor, UUID id) {
    var h = homes.findById(id).orElseThrow(ApiException::missing);
    if (!h.getUserId().equals(actor) && !members.existsByHouseholdIdAndUserId(id, actor))
      throw ApiException.missing();
    return h;
  }

  public ApplianceEntity appliance(UUID actor, UUID id) {
    var a = appliances.findById(id).orElseThrow(ApiException::missing);
    home(actor, a.getHouseholdId());
    return a;
  }

  public SessionEntity session(UUID actor, UUID id) {
    var s = sessions.findById(id).orElseThrow(ApiException::missing);
    appliance(actor, s.getApplianceId());
    return s;
  }

  public UUID ownerId(UUID home) {
    return homes.findById(home).orElseThrow(ApiException::missing).getUserId();
  }

  public List<UUID> participants(UUID home) {
    var h = homes.findById(home).orElseThrow(ApiException::missing);
    var ids = new TreeSet<UUID>();
    ids.add(h.getUserId());
    members.findByHouseholdId(home).forEach(m -> ids.add(m.getUserId()));
    return new ArrayList<>(ids);
  }

  public List<UUID> homes(UUID actor) {
    return homes.visible(actor).stream().map(HouseholdEntity::getId).toList();
  }

  public boolean notifications(UUID actor, UUID home) {
    var h = home(actor, home);
    return h.getUserId().equals(actor)
        || members
            .findByHouseholdIdAndUserId(home, actor)
            .map(HouseholdMemberEntity::isNotificationsEnabled)
            .orElse(false);
  }
}
