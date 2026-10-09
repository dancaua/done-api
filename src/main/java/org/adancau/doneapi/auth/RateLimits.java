package org.adancau.doneapi.auth;

import java.time.Clock;
import org.adancau.doneapi.common.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RateLimits {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public RateLimits(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean consume(String bucket, int limit) {
    return consume(bucket,limit,300);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean consume(String bucket, int limit, int seconds) {
    var now = org.adancau.doneapi.common.DatabaseTime.at(clock.instant());
    var expiry = org.adancau.doneapi.common.DatabaseTime.at(clock.instant().plusSeconds(seconds));
    String key=Crypto.hash(bucket);
    // Upsert holds the row lock until the read and this REQUIRES_NEW transaction commit.
    jdbc.update("INSERT INTO auth_rate_buckets(bucket_key,requests,expires_at) VALUES (?,1,?) "
        +"ON DUPLICATE KEY UPDATE requests=IF(expires_at<=?,1,LEAST(requests+1,?)), expires_at=IF(expires_at<=?,?,expires_at)",
        key,expiry,now,limit+1,now,expiry);
    return Boolean.TRUE.equals(jdbc.queryForObject("SELECT requests<=? FROM auth_rate_buckets WHERE bucket_key=?",Boolean.class,limit,key));
  }
}
