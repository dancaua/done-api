package org.adancau.doneapi.household;

import java.time.Clock;
import java.util.*;
import org.adancau.doneapi.account.AccountDtos.MutationResult;
import org.adancau.doneapi.common.*;
import org.adancau.doneapi.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HouseholdService {
  private final HouseholdRepository homes;
  private final ApplianceRepository appliances;
  private final MutationService mutations;
  private final Clock clock;

  public HouseholdService(
      HouseholdRepository homes,
      ApplianceRepository appliances,
      MutationService mutations,
      Clock clock) {
    this.homes = homes;
    this.appliances = appliances;
    this.mutations = mutations;
    this.clock = clock;
  }

  public HouseholdEntity owned(UUID owner, UUID id) {
    return homes.findByIdAndUserId(id, owner).orElseThrow(ApiException::missing);
  }

  public HouseholdEntity defaultHome(UUID owner) {
    return homes
        .findByIdAndUserId(owner, owner)
        .orElseGet(
            () ->
                homes.findByUserIdOrderByCreatedAtAscIdAsc(owner).stream()
                    .findFirst()
                    .orElseThrow(ApiException::missing));
  }

  public void createDefault(UserEntity user) {
    var h = new HouseholdEntity();
    h.setId(user.getId());
    h.setUserId(user.getId());
    h.setName("My home");
    h.setLocalizationKey("household.default");
    h.setCreatedAt(clock.instant());
    homes.saveAndFlush(h);
  }

  private HouseholdDtos.HouseholdView view(HouseholdEntity h) {
    return new HouseholdDtos.HouseholdView(
        h.getId(),
        h.getName(),
        h.getLocalizationKey(),
        appliances.countByUserIdAndHouseholdId(h.getUserId(), h.getId()),
        h.getCreatedAt(),
        h.getVersion(), h.getUserId());
  }

  @Transactional(readOnly = true)
  public List<HouseholdDtos.HouseholdView> list(UUID owner) {
    return homes.findByUserIdOrderByCreatedAtAscIdAsc(owner).stream().map(this::view).toList();
  }

  @Transactional(readOnly = true)
  public HouseholdDtos.HouseholdView get(UUID owner, UUID id) {
    return view(owned(owner, id));
  }

  public HouseholdDtos.HouseholdView create(UUID owner, UUID key, HouseholdDtos.CreateHousehold r) {
    return mutations.execute(
        owner,
        key,
        "household.create",
        r,
        HouseholdDtos.HouseholdView.class,
        u -> {
          if (homes.countByUserId(owner) >= 20)
            throw ApiException.conflict("household_limit", "Maximum 20 households.");
          String name = Names.clean(r.name(), 60, true);
          if (homes.existsByUserIdAndNameIgnoreCase(owner, name))
            throw ApiException.conflict(
                "duplicate_household", "A household with this name already exists.");
          var h = new HouseholdEntity();
          h.setUserId(owner);
          h.setName(name);
          h.setCreatedAt(clock.instant());
          homes.saveAndFlush(h);
          return view(h);
        });
  }

  public HouseholdDtos.HouseholdView rename(
      UUID owner, UUID id, UUID key, HouseholdDtos.RenameHousehold r) {
    return mutations.execute(
        owner,
        key,
        "household.rename:" + id,
        r,
        HouseholdDtos.HouseholdView.class,
        u -> {
          var h = owned(owner, id);
          if (h.getVersion() != r.version())
            throw ApiException.conflict("stale_version", "Reload the household before editing it.");
          String name = Names.clean(r.name(), 60, true);
          if (!h.getName().equalsIgnoreCase(name)
              && homes.existsByUserIdAndNameIgnoreCase(owner, name))
            throw ApiException.conflict(
                "duplicate_household", "A household with this name already exists.");
          h.setName(name);
          h.setLocalizationKey(null);
          homes.flush();
          return view(h);
        });
  }

  public MutationResult delete(UUID owner, UUID id, UUID key) {
    return mutations.execute(
        owner,
        key,
        "household.delete:" + id,
        Map.of(),
        MutationResult.class,
        u -> {
          var h = owned(owner, id);
          if (homes.countByUserId(owner) <= 1)
            throw ApiException.conflict("last_household", "Keep at least one household.");
          if (appliances.countByUserIdAndHouseholdId(owner, id) > 0)
            throw ApiException.conflict(
                "household_not_empty", "Move or delete the appliances first.");
          homes.delete(h);
          homes.flush();
          return new MutationResult(u.getRevision());
        });
  }
}
