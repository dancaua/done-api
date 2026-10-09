package org.adancau.doneapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(Auth auth, Apple apple, Jobs jobs) {
  public record Auth(
      String issuer, String audience, String secret, Duration accessTtl, Duration refreshTtl) {}

  public record Apple(
      boolean enabled,
      String clientId,
      String teamId,
      String keyId,
      String privateKeyPath,
      String encryptionKey,
      String jwkSetUri,
      String tokenUri,
      String revokeUri, String webClientId, String webRedirectUri) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public Apple {}
    public Apple(boolean enabled, String clientId, String teamId, String keyId, String privateKeyPath,
        String encryptionKey, String jwkSetUri, String tokenUri, String revokeUri) {
      this(enabled,clientId,teamId,keyId,privateKeyPath,encryptionKey,jwkSetUri,tokenUri,revokeUri,"","");
    }
  }

  public record Jobs(boolean enabled, long intervalMs) {}
}
