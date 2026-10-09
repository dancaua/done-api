package org.adancau.doneapi.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AppleChallengeRepository extends JpaRepository<AppleChallengeEntity, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from AppleChallengeEntity c where c.id=:id")
  Optional<AppleChallengeEntity> lockById(@Param("id") UUID id);

  @Modifying
  @Query("delete from AppleChallengeEntity c where c.expiresAt<:before")
  int deleteExpired(@Param("before") Instant before);
}
