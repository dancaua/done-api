package org.adancau.doneapi.persistence;
import java.time.Instant;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface SessionShareRepository extends JpaRepository<SessionShareEntity,String> {
  Optional<SessionShareEntity> findByCommandId(UUID id);
  Optional<SessionShareEntity> findByOwnerIdAndSessionIdAndRevokedAtIsNull(UUID owner, UUID session);
  List<SessionShareEntity> findByOwnerIdAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(UUID owner, Instant now);
  @Query("select s.token from SessionShareEntity s where s.commandId=:id")
  Optional<String> findTokenByCommandId(@Param("id") UUID id);
  long countByOwnerIdIsNull();
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from SessionShareEntity s where s.token=:token")
  Optional<SessionShareEntity> lockByToken(@Param("token") String token);
  @Modifying @Query("delete from SessionShareEntity s where s.expiresAt<=:now")
  int deleteExpired(@Param("now") Instant now);
}
