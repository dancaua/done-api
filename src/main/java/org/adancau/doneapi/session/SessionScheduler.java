package org.adancau.doneapi.session;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SessionScheduler {
  private final SessionEventProcessor processor;

  public SessionScheduler(SessionEventProcessor processor) {
    this.processor = processor;
  }

  @Scheduled(fixedDelayString = "${app.jobs.interval-ms:30000}")
  public void tick() {
    processor.processDue();
  }
}
