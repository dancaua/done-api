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
    var now = java.sql.Timestamp.from(clock.instant());
    var expiry = java.sql.Timestamp.from(clock.instant().plusSeconds(300));
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "INSERT INTO auth_rate_buckets(bucket_key,requests,expires_at) VALUES (?,1,?) ON"
                + " CONFLICT(bucket_key) DO UPDATE SET requests=CASE WHEN"
                + " auth_rate_buckets.expires_at<=? THEN 1 ELSE"
                + " LEAST(auth_rate_buckets.requests+1,?) END, expires_at=CASE WHEN"
                + " auth_rate_buckets.expires_at<=? THEN EXCLUDED.expires_at ELSE"
                + " auth_rate_buckets.expires_at END RETURNING requests<=?",
            Boolean.class,
            Crypto.hash(bucket),
            expiry,
            now,
            limit + 1,
            now,
            limit));
  }
}
