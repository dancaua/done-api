package org.adancau.doneapi.push;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class PushScheduler {
  private final PushService push;

  public PushScheduler(PushService p) {
    push = p;
  }

  @Scheduled(fixedDelayString = "${app.push.interval-ms:15000}")
  public void deliver() {
    push.deliver();
  }
}
