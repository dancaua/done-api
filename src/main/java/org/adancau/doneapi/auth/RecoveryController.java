package org.adancau.doneapi.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class RecoveryController {
  private final PasswordRecoveryService recovery;
  public RecoveryController(PasswordRecoveryService recovery) { this.recovery=recovery; }
  @PostMapping("/forgot-password")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public AuthDtos.RecoveryAccepted request(@Valid @RequestBody AuthDtos.ForgotPassword request) {
    recovery.request(request.email());
    return new AuthDtos.RecoveryAccepted("recovery_requested","If this address has a password account, a recovery email will arrive shortly.");
  }
  @PostMapping("/reset-password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reset(@Valid @RequestBody AuthDtos.ResetPassword request) {
    recovery.reset(request.token(),request.newPassword());
  }
}
