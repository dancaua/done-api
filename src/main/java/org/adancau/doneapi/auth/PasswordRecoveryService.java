package org.adancau.doneapi.auth;

import java.time.*;
import java.util.*;
import org.adancau.doneapi.common.*;
import org.adancau.doneapi.persistence.*;
import org.adancau.doneapi.site.SiteProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PasswordRecoveryService {
  private final JdbcTemplate jdbc;
  private final UserRepository users;
  private final AuthSessionRepository sessions;
  private final PasswordEncoder passwords;
  private final RateLimits rates;
  private final Clock clock;
  private final RecoveryProperties props;
  private final SiteProperties site;
  private final JavaMailSender mail;
  private final TransactionTemplate tx;
  private final io.micrometer.core.instrument.Counter failures;
  private record Job(UUID id,String email,String kind,UUID lease,int attempt) {}
  private record Delivery(String email,String language,String token) {}

  public PasswordRecoveryService(JdbcTemplate jdbc,UserRepository users,AuthSessionRepository sessions,
      PasswordEncoder passwords,RateLimits rates,Clock clock,RecoveryProperties props,SiteProperties site,
      JavaMailSender mail,org.springframework.transaction.PlatformTransactionManager manager,io.micrometer.core.instrument.MeterRegistry metrics) {
    this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.passwords=passwords;this.rates=rates;
    this.clock=clock;this.props=props;this.site=site;this.mail=mail;this.tx=new TransactionTemplate(manager);
    failures=metrics.counter("done.recovery.delivery.failures");
  }

  public void request(String rawEmail) {
    available();
    String email=rawEmail.strip().toLowerCase(Locale.ROOT);
    // Persistent per-account caps supplement the pre-authentication IP/global gate.
    // Deliberately return the same accepted response when a cap is reached.
    if (!rates.consume("recovery:"+email,3) || !rates.consume("recovery-daily:"+email,10,86400)) return;
    tx.executeWithoutResult(status -> enqueue(email,"reset"));
  }

  public void reset(String token,String newPassword) {
    available();AuthService.validPassword(newPassword);
    String hash=Crypto.hash(token);
    tx.executeWithoutResult(status -> {
      // Take the queue lock before user/queue row locks to preserve a consistent lock order.
      jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))","done-recovery-queue");
      var owners=jdbc.queryForList("SELECT user_id FROM password_reset_tokens WHERE token_hash=? AND expires_at>?",UUID.class,hash,now());
      if (owners.isEmpty()) throw invalidToken();
      UUID owner=owners.getFirst();
      var user=users.lockById(owner).orElseThrow(PasswordRecoveryService::invalidToken);
      // Check again under the same user lock used by login, refresh and password changes.
      if (user.getPasswordHash()==null || jdbc.update("DELETE FROM password_reset_tokens WHERE token_hash=? AND user_id=? AND expires_at>?",hash,owner,now())!=1)
        throw invalidToken();
      user.setPasswordHash(passwords.encode(newPassword));user.setRevision(user.getRevision()+1);
      sessions.revokeAll(owner,clock.instant());
      invalidate(owner,user.getEmail());
      enqueue(user.getEmail(),"changed");
    });
  }

  /** Caller holds the user row lock and transaction. Also cancels previously queued recovery emails. */
  public void invalidate(UUID owner,String email) {
    jdbc.update("DELETE FROM password_reset_tokens WHERE user_id=?",owner);
    if(email!=null)jdbc.update("DELETE FROM recovery_mail_queue WHERE email=?",email);
  }

  private void enqueue(String email,String kind) {
    // Serialize capacity checks across instances; bounded queue, no account lookup on the HTTP path.
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))","done-recovery-queue");
    jdbc.update("DELETE FROM recovery_mail_queue WHERE expires_at<=?",now());
    if (jdbc.queryForObject("SELECT count(*) FROM recovery_mail_queue",Long.class)>=props.queueCapacity())
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"recovery_busy","Recovery temporarily unavailable. Please retry.");
    var instant=clock.instant();
    jdbc.update("INSERT INTO recovery_mail_queue(id,email,kind,created_at,expires_at,next_attempt_at) VALUES (?,?,?,?,?,?) ON CONFLICT(email,kind) DO NOTHING",
        UUID.randomUUID(),email,kind,DatabaseTime.at(instant),DatabaseTime.at(instant.plusSeconds(3600)),DatabaseTime.at(instant));
  }

  /** Claims one durable job. SMTP is outside database transactions and has finite timeouts. */
  public boolean deliverNext() {
    if (!props.enabled()) return false;
    Job job=tx.execute(status -> {
      UUID lease=UUID.randomUUID();
      var jobs=jdbc.query("WITH candidate AS (SELECT id FROM recovery_mail_queue WHERE next_attempt_at<=? AND expires_at>? AND attempts<3 ORDER BY next_attempt_at FOR UPDATE SKIP LOCKED LIMIT 1) "
          +"UPDATE recovery_mail_queue q SET lease_id=?,attempts=attempts+1,next_attempt_at=? FROM candidate c WHERE q.id=c.id RETURNING q.id,q.email,q.kind,q.attempts",
          (rs,n) -> new Job(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("kind"),lease,rs.getInt("attempts")),
          now(),now(),lease,DatabaseTime.at(clock.instant().plusSeconds(120)));
      return jobs.isEmpty() ? null : jobs.getFirst();
    });
    if (job==null) return false;
    try {
      Delivery delivery=tx.execute(status -> {
        var candidate=users.findByEmail(job.email()).orElse(null);
        if (candidate==null) return null;
        var user=users.lockById(candidate.getId()).orElse(null);
        if (user==null || user.getPasswordHash()==null) return null; // Apple-only accounts keep Apple's recovery flow.
        var active=jdbc.queryForList("SELECT id FROM recovery_mail_queue WHERE id=? AND lease_id=? AND expires_at>? FOR UPDATE",UUID.class,job.id(),job.lease(),now());
        if (active.isEmpty()) return null; // Cancelled by a password change/deletion, or reclaimed after a crash.
        String token=null;
        if(job.kind().equals("reset")) {
          token=Crypto.randomToken();
          jdbc.update("INSERT INTO password_reset_tokens(token_hash,user_id,created_at,expires_at) VALUES (?,?,?,?)",
              Crypto.hash(token),user.getId(),now(),DatabaseTime.at(clock.instant().plus(props.tokenTtl())));
        }
        return new Delivery(user.getEmail(),user.getLanguage(),token);
      });
      if (delivery!=null) {
        var message=new SimpleMailMessage();message.setFrom(props.from());message.setTo(delivery.email());
        String[] copy=RecoveryMailCopy.forLanguage(delivery.language());
        message.setSubject(delivery.token()==null ? copy[2] : copy[0]);
        message.setText(delivery.token()==null ? copy[3] : copy[1].replace("{minutes}",Long.toString(props.tokenTtl().toMinutes()))+"\n\n"+site.origin().replaceAll("/$", "")+
            "/reset-password?lang="+delivery.language()+"#token="+delivery.token()+"\n\n"+copy[4]);
        mail.send(message);
      }
      jdbc.update("DELETE FROM recovery_mail_queue WHERE id=? AND lease_id=?",job.id(),job.lease());
    } catch (RuntimeException exception) {
      // Never log SMTP exceptions: providers may include recipients or message contents.
      failures.increment();
      jdbc.update("UPDATE recovery_mail_queue SET lease_id=NULL,next_attempt_at=? WHERE id=? AND lease_id=?",
          DatabaseTime.at(clock.instant().plusSeconds(60L*job.attempt())),job.id(),job.lease());
    }
    return true;
  }

  private LocalDateTime now() { return DatabaseTime.at(clock.instant()); }
  private void available() {
    if (!props.enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"recovery_unavailable","Password recovery is temporarily unavailable.");
  }
  private static ApiException invalidToken() {
    return new ApiException(HttpStatus.BAD_REQUEST,"invalid_reset_token","This recovery link is invalid or expired. Request a new one.");
  }
}
