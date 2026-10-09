package org.adancau.doneapi.auth;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import org.adancau.doneapi.account.AccountDtos.UserView;

public final class AuthDtos {
  private AuthDtos() {}

  public record Register(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 12, max = 64) String password,
      @NotBlank String displayName,
      @Pattern(regexp = org.adancau.doneapi.common.SupportedLanguages.PATTERN) String language) {}

  public record Login(
      @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 64) String password) {}

  public record ForgotPassword(@NotBlank @Email @Size(max=254) String email) {}
  public record ResetPassword(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
      @NotBlank @Size(min=12,max=64) String newPassword) {}
  public record RecoveryAccepted(String code,String detail) {}

  public record Refresh(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String refreshToken) {}

  public record Tokens(
      String tokenType,
      String accessToken,
      long expiresIn,
      String refreshToken,
      Instant refreshExpiresAt,
      UserView user) {}

  public record AppleChallenge(UUID challengeId, String nonce, Instant expiresAt) {}

  public record AppleLogin(
      @NotNull UUID challengeId,
      @NotBlank @Size(max = 16000) String identityToken,
      @NotBlank @Size(max = 4096) String authorizationCode,
      String displayName,
      @Pattern(regexp = org.adancau.doneapi.common.SupportedLanguages.PATTERN) String language,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      @Size(max=255) String clientId) {
    public AppleLogin(UUID challengeId, String identityToken, String authorizationCode, String displayName, String language) {
      this(challengeId,identityToken,authorizationCode,displayName,language,null);
    }
  }
}
