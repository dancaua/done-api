package org.adancau.doneapi.persistence;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface ApplianceRepository extends JpaRepository<ApplianceEntity, UUID> {
  List<ApplianceEntity> findByUserIdOrderByCreatedAtAscIdAsc(UUID userId);

  Optional<ApplianceEntity> findByIdAndUserId(UUID id, UUID userId);

  boolean existsByUserIdAndNameIgnoreCase(UUID userId, String name);

  long countByUserId(UUID userId);

  @Query("select a from ApplianceEntity a where a.householdId in :homes order by a.createdAt,a.id")
  List<ApplianceEntity> visible(
      @org.springframework.data.repository.query.Param("homes") Collection<UUID> homes);

  long countByUserIdAndHouseholdId(UUID userId, UUID householdId);

  boolean existsByUserIdAndHouseholdIdAndNameIgnoreCase(UUID userId, UUID householdId, String name);

  List<ApplianceEntity> findByUserIdAndHouseholdIdOrderByCreatedAtAscIdAsc(
      UUID userId, UUID householdId);
}
