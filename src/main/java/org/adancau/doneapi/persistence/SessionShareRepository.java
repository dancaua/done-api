package org.adancau.doneapi.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SessionShareRepository extends JpaRepository<SessionShareEntity, String> {
  Optional<SessionShareEntity> findByCommandId(UUID id);

  Optional<SessionShareEntity> findByOwnerIdAndSessionIdAndRevokedAtIsNull(
      UUID owner, UUID session);

  List<SessionShareEntity> findByOwnerIdAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
      UUID owner, Instant now);

  @Query("select s.token from SessionShareEntity s where s.commandId=:id")
  Optional<String> findTokenByCommandId(@Param("id") UUID id);

  @Query(
      "select q from SessionShareEntity q where q.revokedAt is null and q.expiresAt>:now and"
          + " q.sessionId in (select s.id from SessionEntity s where s.applianceId in (select a.id"
          + " from ApplianceEntity a where a.householdId in :homes)) order by q.createdAt desc")
  List<SessionShareEntity> visible(
      @Param("homes") Collection<UUID> homes, @Param("now") Instant now);

  long countByOwnerIdIsNull();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from SessionShareEntity s where s.token=:token")
  Optional<SessionShareEntity> lockByToken(@Param("token") String token);

  @Modifying
  @Query("delete from SessionShareEntity s where s.expiresAt<=:now")
  int deleteExpired(@Param("now") Instant now);
}
