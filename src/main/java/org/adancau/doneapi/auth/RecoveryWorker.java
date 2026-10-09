package org.adancau.doneapi.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name={"app.recovery.enabled","app.recovery.delivery-enabled"},havingValue="true")
public class RecoveryWorker {
  private final PasswordRecoveryService recovery;
  public RecoveryWorker(PasswordRecoveryService recovery) { this.recovery=recovery; }
  @Scheduled(fixedDelay=1000,scheduler="recoveryScheduler")
  public void deliver() { recovery.deliverNext(); }
}
