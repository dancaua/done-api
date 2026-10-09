package org.adancau.doneapi.session;

import java.time.Clock;
import org.adancau.doneapi.persistence.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class Housekeeping {
  private final Clock clock;
  private final JdbcTemplate jdbc;
  private final AppleChallengeRepository challenges;
  private final MutationReceiptRepository receipts;

  public Housekeeping(
      Clock clock,
      JdbcTemplate jdbc,
      AppleChallengeRepository challenges,
      MutationReceiptRepository receipts) {
    this.clock = clock;
    this.jdbc = jdbc;
    this.challenges = challenges;
    this.receipts = receipts;
  }

  @Scheduled(fixedDelay = 600000)
  @Transactional
  public void clean() {
    var now = clock.instant();
    challenges.deleteExpired(now.minusSeconds(3600));
    receipts.deleteExpired(now.minusSeconds(2592000));
    jdbc.update("DELETE FROM session_shares WHERE expires_at<=?", java.sql.Timestamp.from(now));
    jdbc.update(
        "DELETE FROM auth_rate_buckets WHERE expires_at < ?",
        java.sql.Timestamp.from(now.minusSeconds(300)));
    jdbc.update(
        "DELETE FROM auth_sessions WHERE expires_at < ?",
        java.sql.Timestamp.from(now.minusSeconds(604800)));
  }
}
