package org.adancau.doneapi.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseholdRepository extends JpaRepository<HouseholdEntity, UUID> {
  List<HouseholdEntity> findByUserIdOrderByCreatedAtAscIdAsc(UUID userId);

  Optional<HouseholdEntity> findByIdAndUserId(UUID id, UUID userId);

  boolean existsByUserIdAndNameIgnoreCase(UUID userId, String name);

  long countByUserId(UUID userId);
}
