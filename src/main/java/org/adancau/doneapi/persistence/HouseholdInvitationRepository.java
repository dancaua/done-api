package org.adancau.doneapi.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseholdInvitationRepository
    extends JpaRepository<HouseholdInvitationEntity, UUID> {
  Optional<HouseholdInvitationEntity> findByTokenHash(String hash);

  List<HouseholdInvitationEntity> findByHouseholdIdOrderByCreatedAtDesc(UUID home);
}
