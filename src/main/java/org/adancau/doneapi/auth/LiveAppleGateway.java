package org.adancau.doneapi.auth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;
import org.adancau.doneapi.common.ApiException;
import org.adancau.doneapi.config.AppProperties;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;

@Component
public class LiveAppleGateway implements AppleGateway {
  private final AppProperties.Apple props;
  private final Clock clock;
  private final RestClient http;
  private final JwtDecoder decoder;
  private final ECPrivateKey signingKey;

  public LiveAppleGateway(AppProperties p, Clock clock) {
    this.props = p.apple();
    this.clock = clock;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5000);
    factory.setReadTimeout(5000);
    this.http = RestClient.builder().requestFactory(factory).build();
    if (!props.enabled()) {
      decoder = null;
      signingKey = null;
      return;
    }
    if (props.clientId().isBlank() || props.teamId().isBlank() || props.keyId().isBlank())
      throw new IllegalStateException("Configure Apple client ID, team ID and key ID.");
    try {
      String pem =
          Files.readString(Path.of(props.privateKeyPath()))
              .replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      signingKey =
          (ECPrivateKey)
              KeyFactory.getInstance("EC")
                  .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot load Sign in with Apple P8 private key.");
    }
    var jwt =
        NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(new RestTemplate(factory))
            .build();
    var timestamp = new JwtTimestampValidator(Duration.ofSeconds(10));
    timestamp.setClock(clock);
    jwt.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            timestamp,
            new JwtIssuerValidator("https://appleid.apple.com"),
            token ->
                (token.getAudience().contains(props.clientId()) || (props.webClientId()!=null && !props.webClientId().isBlank() && token.getAudience().contains(props.webClientId())))
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
    decoder = jwt;
  }

  private void enabled() {
    if (!props.enabled())
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "apple_not_configured",
          "Sign in with Apple nu este configurat încă.");
  }

  private Jwt verify(String token, String nonce, String clientId) {
    try {
      var jwt = decoder.decode(token);
      if (!jwt.getAudience().contains(clientId) || jwt.getSubject() == null
          || jwt.getSubject().isBlank()
          || !nonce.equals(jwt.getClaimAsString("nonce"))
          || jwt.getExpiresAt() == null
          || jwt.getIssuedAt() == null
          || jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(10))
          || jwt.getIssuedAt().isBefore(clock.instant().minusSeconds(600)))
        throw ApiException.unauthorized();
      return jwt;
    } catch (JwtException e) {
      throw ApiException.unauthorized();
    }
  }

  private String clientSecret(String clientId) {
    try {
      var claims =
          new JWTClaimsSet.Builder()
              .issuer(props.teamId())
              .subject(clientId)
              .audience("https://appleid.apple.com")
              .issueTime(Date.from(clock.instant()))
              .expirationTime(Date.from(clock.instant().plusSeconds(300)))
              .build();
      var token =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(props.keyId()).build(), claims);
      token.sign(new ECDSASigner(signingKey));
      return token.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("Cannot sign Apple client secret", e);
    }
  }

  @Override
  public Identity authenticate(AuthDtos.AppleLogin request, String nonce) {
    enabled();
    String clientId=request.clientId()==null ? props.clientId() : request.clientId();
    if (!clientId.equals(props.clientId()) && (props.webClientId()==null || !clientId.equals(props.webClientId()) || clientId.isBlank())) throw ApiException.unauthorized();
    var original = verify(request.identityToken(), nonce, clientId);
    var form = new LinkedMultiValueMap<String, String>();
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret(clientId));
    form.add("grant_type", "authorization_code");
    form.add("code", request.authorizationCode());
    if (!clientId.equals(props.clientId())) form.add("redirect_uri",props.webRedirectUri());
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> response =
          http.post()
              .uri(props.tokenUri())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(Map.class);
      if (response == null
          || !(response.get("id_token") instanceof String idToken)
          || !(response.get("refresh_token") instanceof String refresh)
          || refresh.isBlank()) throw ApiException.unauthorized();
      var exchanged = verify(idToken, nonce, clientId);
      if (!original.getSubject().equals(exchanged.getSubject())) throw ApiException.unauthorized();
      String email =
          Boolean.parseBoolean(String.valueOf(exchanged.getClaims().get("email_verified")))
              ? exchanged.getClaimAsString("email")
              : null;
      return new Identity(exchanged.getSubject(), email, refresh, clientId);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 400 && e.getResponseBodyAsString().contains("invalid_grant"))
        throw ApiException.unauthorized();
      throw unavailable();
    } catch (RestClientException e) {
      throw unavailable();
    }
  }

  @Override
  public void revoke(String refreshToken) { revoke(refreshToken,props.clientId()); }
  @Override
  public void revoke(String refreshToken, String clientId) {
    enabled();
    if (!clientId.equals(props.clientId()) && !clientId.equals(props.webClientId())) throw ApiException.unauthorized();
    var form = new LinkedMultiValueMap<String, String>();
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret(clientId));
    form.add("token", refreshToken);
    form.add("token_type_hint", "refresh_token");
    try {
      http.post()
          .uri(props.revokeUri())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      throw unavailable();
    }
  }

  private ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "apple_unavailable",
        "Serviciul Apple nu este disponibil. Încearcă din nou.");
  }
}
