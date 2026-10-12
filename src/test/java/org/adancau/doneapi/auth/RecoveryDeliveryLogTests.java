package org.adancau.doneapi.auth;

import static org.junit.jupiter.api.Assertions.*;
import java.net.SocketTimeoutException;
import java.util.Map;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

@ExtendWith(OutputCaptureExtension.class)
class RecoveryDeliveryLogTests {
  private static final String PRIVATE="private-recipient@invalid.invalid secret-password reset-link#token=private";

  @Test void authenticationFailureIsActionableWithoutExposingProviderDetails(CapturedOutput output) {
    RecoveryDeliveryLog.failed("reset",1,new MailAuthenticationException(PRIVATE));
    assertTrue(output.toString().contains("category=AUTHENTICATION"));
    assertTrue(output.toString().contains("retry=true"));
    assertFalse(output.toString().contains(PRIVATE));
    assertFalse(output.toString().contains("MailAuthenticationException"));
  }

  @Test void nestedTransportErrorsAreClassifiedWithoutLoggingRecipientsOrTokens(CapturedOutput output) {
    var nested=new MessagingException(PRIVATE,new SocketTimeoutException(PRIVATE));
    RecoveryDeliveryLog.failed("reset",3,new MailSendException(Map.of(PRIVATE,nested)));
    assertTrue(output.toString().contains("category=NETWORK_TIMEOUT"));
    assertTrue(output.toString().contains("attempt=3"));
    assertTrue(output.toString().contains("retry=false"));
    assertFalse(output.toString().contains("private-recipient"));
    assertFalse(output.toString().contains("secret-password"));
    assertFalse(output.toString().contains("#token="));
    assertFalse(output.toString().contains("MessagingException"));
  }

  @Test void databaseErrorsAreDistinguishedFromMailDeliveryErrors(CapturedOutput output) {
    RecoveryDeliveryLog.failed("changed",1,new org.springframework.dao.DataAccessResourceFailureException(PRIVATE));
    assertTrue(output.toString().contains("category=DATABASE"));
    assertFalse(output.toString().contains(PRIVATE));
  }

  @Test void acceptedAndLimitedEventsExplainOtherwiseSilentResponses(CapturedOutput output) {
    RecoveryDeliveryLog.requested();RecoveryDeliveryLog.accepted("reset");RecoveryDeliveryLog.limited(true);
    assertTrue(output.toString().contains("event=queued"));
    assertTrue(output.toString().contains("event=smtp_accepted"));
    assertTrue(output.toString().contains("event=rate_limited window=daily"));
  }
}
