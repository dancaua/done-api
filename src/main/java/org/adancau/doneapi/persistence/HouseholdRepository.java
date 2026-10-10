package org.adancau.doneapi.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseholdRepository extends JpaRepository<HouseholdEntity, UUID> {
  List<HouseholdEntity> findByUserIdOrderByCreatedAtAscIdAsc(UUID userId);

  Optional<HouseholdEntity> findByIdAndUserId(UUID id, UUID userId);

  boolean existsByUserIdAndNameIgnoreCase(UUID userId, String name);

  long countByUserId(UUID userId);

  @org.springframework.data.jpa.repository.Query(
      "select h from HouseholdEntity h where h.userId=:actor or h.id in (select m.householdId from"
          + " HouseholdMemberEntity m where m.userId=:actor) order by h.createdAt,h.id")
  List<HouseholdEntity> visible(
      @org.springframework.data.repository.query.Param("actor") UUID actor);
}
