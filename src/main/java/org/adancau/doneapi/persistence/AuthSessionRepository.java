package org.adancau.doneapi.persistence;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, UUID> {
  @Modifying
  @Query(
      "update AuthSessionEntity s set s.revokedAt=:now where s.userId=:userId and s.revokedAt is"
          + " null")
  int revokeAll(@Param("userId") UUID userId, @Param("now") Instant now);
}
