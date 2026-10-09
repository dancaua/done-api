package org.adancau.doneapi.account;

import jakarta.validation.Valid;
import java.util.UUID;
import org.adancau.doneapi.auth.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me")
public class AccountController {
  private final AccountService service;
  private final AppleAuthService apple;

  public AccountController(AccountService service, AppleAuthService apple) {
    this.service = service;
    this.apple = apple;
  }

  @GetMapping
  public AccountDtos.UserView get(@AuthenticationPrincipal Jwt jwt) {
    return service.get(UUID.fromString(jwt.getSubject()));
  }

  @PatchMapping
  public AccountDtos.UserView update(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody AccountDtos.UpdateProfile r) {
    return service.update(UUID.fromString(jwt.getSubject()), key, r);
  }

  @PostMapping("/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void password(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AccountDtos.ChangePassword r) {
    service.changePassword(UUID.fromString(jwt.getSubject()), r);
  }

  @PostMapping("/identities/apple")
  public AccountDtos.UserView apple(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AuthDtos.AppleLogin r) {
    return apple.link(
        UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")), r);
  }

  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody(required = false) AccountDtos.DeleteAccount r) {
    service.delete(
        UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")), r);
  }
}
