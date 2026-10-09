package org.adancau.doneapi.auth;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService auth;
  private final AppleAuthService apple;

  public AuthController(AuthService auth, AppleAuthService apple) {
    this.auth = auth;
    this.apple = apple;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public AuthDtos.Tokens register(@Valid @RequestBody AuthDtos.Register r) {
    return auth.register(r);
  }

  @PostMapping("/login")
  public AuthDtos.Tokens login(@Valid @RequestBody AuthDtos.Login r) {
    return auth.login(r);
  }

  @PostMapping("/refresh")
  public AuthDtos.Tokens refresh(@Valid @RequestBody AuthDtos.Refresh r) {
    return auth.refresh(r.refreshToken());
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(@AuthenticationPrincipal Jwt jwt) {
    auth.logout(
        UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")), false);
  }

  @PostMapping("/logout-all")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logoutAll(@AuthenticationPrincipal Jwt jwt) {
    auth.logout(
        UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")), true);
  }

  @PostMapping("/apple/challenge")
  public AuthDtos.AppleChallenge challenge() {
    return apple.challenge();
  }

  @PostMapping("/apple/delete-login")
  public AuthDtos.Tokens deleteLogin(@Valid @RequestBody AuthDtos.AppleLogin r) {
    return apple.loginExisting(r);
  }

  @PostMapping("/apple")
  public AuthDtos.Tokens apple(@Valid @RequestBody AuthDtos.AppleLogin r) {
    return apple.login(r);
  }
}
