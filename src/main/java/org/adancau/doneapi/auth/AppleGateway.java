package org.adancau.doneapi.auth;

public interface AppleGateway {
  record Identity(String subject, String email, String refreshToken, String clientId) {
    public Identity(String subject, String email, String refreshToken) { this(subject,email,refreshToken,null); }
  }

  Identity authenticate(AuthDtos.AppleLogin request, String nonce);

  void revoke(String refreshToken);
  default void revoke(String refreshToken, String clientId) { revoke(refreshToken); }
}
