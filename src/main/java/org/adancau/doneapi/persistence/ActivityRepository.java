package org.adancau.doneapi.persistence;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ActivityRepository extends JpaRepository<ActivityEntity, UUID> {
  Page<ActivityEntity> findByUserIdOrderByOccurredAtDescIdDesc(UUID userId, Pageable page);

  Optional<ActivityEntity> findByIdAndUserId(UUID id, UUID userId);

  Optional<ActivityEntity> findBySessionIdAndUserIdAndKind(UUID sessionId, UUID userId, String kind);

  @Modifying
  @Query("update ActivityEntity e set e.readAt=:now where e.userId=:userId and e.readAt is null")
  int markAllRead(@Param("userId") UUID userId, @Param("now") Instant now);

  @Query(
      "select e from ActivityEntity e where e.userId=:userId and e.applianceId in (select a.id from"
          + " ApplianceEntity a where a.userId=:userId and a.householdId=:householdId) order by"
          + " e.occurredAt desc,e.id desc")
  Page<ActivityEntity> findByHousehold(
      @Param("userId") UUID userId, @Param("householdId") UUID householdId, Pageable page);

  @Modifying
  @Query(
      "update ActivityEntity e set e.readAt=:now where e.userId=:userId and e.readAt is null and"
          + " e.applianceId in (select a.id from ApplianceEntity a where a.userId=:userId and"
          + " a.householdId=:householdId)")
  int markHouseholdRead(
      @Param("userId") UUID userId,
      @Param("householdId") UUID householdId,
      @Param("now") Instant now);

  long countByUserIdAndReadAtIsNull(UUID userId);
}
