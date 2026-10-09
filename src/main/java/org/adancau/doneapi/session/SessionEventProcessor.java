package org.adancau.doneapi.session;

import java.time.Clock;
import org.adancau.doneapi.persistence.*;
import org.slf4j.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SessionEventProcessor {
  private final SessionRepository sessions;
  private final ApplianceRepository appliances;
  private final UserRepository users;
  private final SessionEvents events;
  private final Clock clock;
  private final TransactionTemplate tx;
  private static final Logger log = LoggerFactory.getLogger(SessionEventProcessor.class);

  public SessionEventProcessor(
      SessionRepository sessions,
      ApplianceRepository appliances,
      UserRepository users,
      SessionEvents events,
      Clock clock,
      PlatformTransactionManager manager) {
    this.sessions = sessions;
    this.appliances = appliances;
    this.users = users;
    this.events = events;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
  }

  public void processDue() {
    for (var id : sessions.findDue(clock.instant(), PageRequest.of(0, 100))) {
      var candidate = sessions.findById(id).orElse(null);
      if (candidate == null) continue;
      try {
        tx.executeWithoutResult(
            status -> {
              var user = users.lockById(candidate.getUserId()).orElse(null);
              if (user == null) return;
              var current = sessions.findByIdAndUserId(id, user.getId()).orElse(null);
              if (current == null) return;
              var a =
                  appliances.findByIdAndUserId(current.getApplianceId(), user.getId()).orElse(null);
              if (a == null) return;
              if (events.process(current, a, clock.instant()))
                user.setRevision(user.getRevision() + 1);
            });
      } catch (Exception e) {
        log.error("Failed to process scheduled session {}", id, e);
      }
    }
  }
}
