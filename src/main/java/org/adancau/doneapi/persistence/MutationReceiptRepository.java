package org.adancau.doneapi.persistence;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MutationReceiptRepository extends JpaRepository<MutationReceiptEntity, UUID> {
  Optional<MutationReceiptEntity> findByUserIdAndRequestId(UUID userId, UUID requestId);

  @Modifying
  @Query("delete from MutationReceiptEntity r where r.createdAt<:before")
  int deleteExpired(@Param("before") Instant before);
}
