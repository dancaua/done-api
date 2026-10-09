package org.adancau.doneapi.persistence;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface AppleIdentityRepository extends JpaRepository<AppleIdentityEntity, UUID> {
  Optional<AppleIdentityEntity> findBySubject(String subject);

  Optional<AppleIdentityEntity> findByUserId(UUID userId);
}
