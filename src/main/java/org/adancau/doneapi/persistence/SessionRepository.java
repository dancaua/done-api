package org.adancau.doneapi.persistence;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<SessionEntity, UUID> {
  Optional<SessionEntity> findByIdAndUserId(UUID id, UUID userId);

  Optional<SessionEntity> findByApplianceIdAndCollectedAtIsNullAndCanceledAtIsNull(UUID id);

  Page<SessionEntity> findByUserIdAndApplianceIdOrderByStartedAtDescIdDesc(
      UUID userId, UUID applianceId, Pageable page);

  List<SessionEntity> findByUserIdAndCollectedAtIsNullAndCanceledAtIsNull(UUID userId);

  List<SessionEntity>
      findByUserIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanAndCanceledAtIsNull(
          UUID userId, Instant start, Instant end);

  List<SessionEntity> findByUserIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanEqualAndCanceledAtIsNull(
      UUID userId, Instant start, Instant end);

  List<SessionEntity> findByUserIdOrderByStartedAtDescIdDesc(UUID userId, Pageable page);

  @Query("select s.id from SessionEntity s where s.nextEventAt<=:now order by s.nextEventAt,s.id")
  List<UUID> findDue(@Param("now") Instant now, Pageable page);
}
