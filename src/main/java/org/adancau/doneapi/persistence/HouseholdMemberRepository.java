package org.adancau.doneapi.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseholdMemberRepository extends JpaRepository<HouseholdMemberEntity, UUID> {
  boolean existsByHouseholdIdAndUserId(UUID home, UUID user);

  Optional<HouseholdMemberEntity> findByHouseholdIdAndUserId(UUID home, UUID user);

  List<HouseholdMemberEntity> findByHouseholdId(UUID home);

  long countByHouseholdId(UUID home);
}
