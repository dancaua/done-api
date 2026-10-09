package org.adancau.doneapi;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.ECGenParameterSpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.adancau.doneapi.auth.*;
import org.adancau.doneapi.common.ApiException;
import org.adancau.doneapi.config.AppProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class AppleGatewayTests {
  @TempDir Path temp;
  HttpServer server;
  RSAKey rsa;
  KeyPair ec;
  final Instant now = Instant.parse("2026-10-05T12:00:00Z");
  final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  LiveAppleGateway gateway;
  AppProperties props;
  String exchanged;
  int tokenStatus = 200, revokeStatus = 200;
  Map<String, String> lastForm;
  AtomicInteger calls = new AtomicInteger();
  JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void setup() throws Exception {
    rsa = new RSAKeyGenerator(2048).keyID("apple-test-key").generate();
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    ec = generator.generateKeyPair();
    var pem = temp.resolve("AuthKey.p8");
    Files.writeString(
        pem,
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(ec.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/keys",
        exchange -> {
          byte[] body = new JWKSet(rsa.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/token",
        exchange -> {
          calls.incrementAndGet();
          lastForm =
              parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          var response =
              tokenStatus == 200
                  ? Map.of("id_token", exchanged, "refresh_token", "real-apple-refresh")
                  : Map.of(
                      "error", tokenStatus == 400 ? "invalid_grant" : "temporarily_unavailable");
          byte[] body = json.writeValueAsBytes(response);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(tokenStatus, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/revoke",
        exchange -> {
          lastForm =
              parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(revokeStatus, -1);
          exchange.close();
        });
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    props =
        new AppProperties(
            null,
            new AppProperties.Apple(
                true,
                "ro.done.app",
                "TEAM123",
                "KEY123",
                pem.toString(),
                "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=",
                base + "/keys",
                base + "/token",
                base + "/revoke"),
            null);
    gateway = new LiveAppleGateway(props, clock);
    exchanged =
        signed(
            "https://appleid.apple.com",
            "ro.done.app",
            "nonce",
            "apple-user",
            now,
            now.plusSeconds(300),
            rsa);
  }

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  Map<String, String> parse(String body) {
    var result = new HashMap<String, String>();
    for (String item : body.split("&")) {
      var pair = item.split("=", 2);
      result.put(
          URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
          URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
    }
    return result;
  }

  String signed(
      String issuer,
      String audience,
      String nonce,
      String sub,
      Instant issued,
      Instant expiry,
      RSAKey key)
      throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject(sub)
            .issueTime(Date.from(issued))
            .expirationTime(Date.from(expiry))
            .claim("nonce", nonce)
            .claim("email", "apple@example.com")
            .claim("email_verified", "true")
            .build();
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("apple-test-key").build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  AuthDtos.AppleLogin request(String token) {
    return new AuthDtos.AppleLogin(UUID.randomUUID(), token, "authorization-code", "Alex", null);
  }

  @Test
  void verifiesRealSignaturesAndExchangesCodeWithSignedAppleClientSecret() throws Exception {
    var identity = gateway.authenticate(request(exchanged), "nonce");
    assertEquals("apple-user", identity.subject());
    assertEquals("apple@example.com", identity.email());
    assertEquals("real-apple-refresh", identity.refreshToken());
    assertEquals("ro.done.app", lastForm.get("client_id"));
    assertEquals("authorization_code", lastForm.get("grant_type"));
    assertEquals("authorization-code", lastForm.get("code"));
    var client = SignedJWT.parse(lastForm.get("client_secret"));
    assertEquals(JWSAlgorithm.ES256, client.getHeader().getAlgorithm());
    assertTrue(client.verify(new ECDSAVerifier((ECPublicKey) ec.getPublic())));
    assertEquals("TEAM123", client.getJWTClaimsSet().getIssuer());
    assertEquals("ro.done.app", client.getJWTClaimsSet().getSubject());
    assertEquals(List.of("https://appleid.apple.com"), client.getJWTClaimsSet().getAudience());
    gateway.revoke(identity.refreshToken());
    assertEquals("real-apple-refresh", lastForm.get("token"));
    assertEquals("refresh_token", lastForm.get("token_type_hint"));
  }

  @Test
  void rejectsNonceAudienceIssuerExpiryFutureIssuedAtAndForgedSignatureBeforeCodeExchange()
      throws Exception {
    var badTokens =
        List.of(
            signed(
                "https://appleid.apple.com",
                "ro.done.app",
                "wrong",
                "apple-user",
                now,
                now.plusSeconds(300),
                rsa),
            signed(
                "https://appleid.apple.com",
                "other-app",
                "nonce",
                "apple-user",
                now,
                now.plusSeconds(300),
                rsa),
            signed(
                "https://attacker.example",
                "ro.done.app",
                "nonce",
                "apple-user",
                now,
                now.plusSeconds(300),
                rsa),
            signed(
                "https://appleid.apple.com",
                "ro.done.app",
                "nonce",
                "apple-user",
                now.minusSeconds(300),
                now.minusSeconds(60),
                rsa),
            signed(
                "https://appleid.apple.com",
                "ro.done.app",
                "nonce",
                "apple-user",
                now.plusSeconds(60),
                now.plusSeconds(300),
                rsa),
            signed(
                "https://appleid.apple.com",
                "ro.done.app",
                "nonce",
                "apple-user",
                now,
                now.plusSeconds(300),
                new RSAKeyGenerator(2048).generate()));
    for (String token : badTokens)
      assertEquals(
          401,
          assertThrows(ApiException.class, () -> gateway.authenticate(request(token), "nonce"))
              .getStatus()
              .value());
    assertEquals(0, calls.get());
  }

  @Test
  void exchangedIdentityMustMatchOriginalSubject() throws Exception {
    String original = exchanged;
    exchanged =
        signed(
            "https://appleid.apple.com",
            "ro.done.app",
            "nonce",
            "another-user",
            now,
            now.plusSeconds(300),
            rsa);
    assertEquals(
        401,
        assertThrows(ApiException.class, () -> gateway.authenticate(request(original), "nonce"))
            .getStatus()
            .value());
  }

  @Test
  void invalidSingleUseCodeIsUnauthorizedAndUpstreamFailureIsRetryable() {
    tokenStatus = 400;
    assertEquals(
        401,
        assertThrows(ApiException.class, () -> gateway.authenticate(request(exchanged), "nonce"))
            .getStatus()
            .value());
    tokenStatus = 503;
    assertEquals(
        503,
        assertThrows(ApiException.class, () -> gateway.authenticate(request(exchanged), "nonce"))
            .getStatus()
            .value());
    revokeStatus = 503;
    assertEquals(
        503,
        assertThrows(ApiException.class, () -> gateway.revoke("credential")).getStatus().value());
  }

  @Test
  void rejectsSymmetricAlgorithmConfusion() throws Exception {
    var jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .issuer("https://appleid.apple.com")
                .audience("ro.done.app")
                .subject("apple-user")
                .expirationTime(Date.from(now.plusSeconds(300)))
                .issueTime(Date.from(now))
                .claim("nonce", "nonce")
                .build());
    jwt.sign(new MACSigner(new byte[32]));
    assertEquals(
        401,
        assertThrows(
                ApiException.class, () -> gateway.authenticate(request(jwt.serialize()), "nonce"))
            .getStatus()
            .value());
    assertEquals(0, calls.get());
  }

  void configureWebClient() {
    var a=props.apple();
    props=new AppProperties(null,new AppProperties.Apple(a.enabled(),a.clientId(),a.teamId(),a.keyId(),a.privateKeyPath(),a.encryptionKey(),a.jwkSetUri(),a.tokenUri(),a.revokeUri(),"ro.done.web","https://done.app/delete-account"),null);
    gateway=new LiveAppleGateway(props,clock);
  }

  @Test
  void webCodeExchangeAndRevocationUseServicesIdAndRegisteredRedirect() throws Exception {
    configureWebClient();
    exchanged=signed("https://appleid.apple.com","ro.done.web","nonce","apple-user",now,now.plusSeconds(300),rsa);
    var request=new AuthDtos.AppleLogin(UUID.randomUUID(),exchanged,"authorization-code",null,null,"ro.done.web");
    var identity=gateway.authenticate(request,"nonce");
    assertEquals("ro.done.web",identity.clientId());
    assertEquals("https://done.app/delete-account",lastForm.get("redirect_uri"));
    assertEquals("ro.done.web",SignedJWT.parse(lastForm.get("client_secret")).getJWTClaimsSet().getSubject());
    gateway.revoke(identity.refreshToken(),identity.clientId());
    assertEquals("ro.done.web",lastForm.get("client_id"));
    assertTrue(SignedJWT.parse(lastForm.get("client_secret")).verify(new ECDSAVerifier((ECPublicKey)ec.getPublic())));
  }

  @Test
  void aValidWebTokenCannotBeExchangedAsNativeOrAnUnregisteredClient() throws Exception {
    configureWebClient();
    String token=signed("https://appleid.apple.com","ro.done.web","nonce","apple-user",now,now.plusSeconds(300),rsa);
    assertEquals(401,assertThrows(ApiException.class,()->gateway.authenticate(request(token),"nonce")).getStatus().value());
    var other=new AuthDtos.AppleLogin(UUID.randomUUID(),token,"code",null,null,"other-client");
    assertEquals(401,assertThrows(ApiException.class,()->gateway.authenticate(other,"nonce")).getStatus().value());
    assertEquals(0,calls.get());
    var web=new AuthDtos.AppleLogin(UUID.randomUUID(),token,"code",null,null,"ro.done.web");
    // The exchange response has a native audience, despite a valid incoming web token.
    assertEquals(401,assertThrows(ApiException.class,()->gateway.authenticate(web,"nonce")).getStatus().value());
    assertEquals(1,calls.get());
  }

  @Test
  void encryptedAppleCredentialDetectsTamperingAndUsesRandomIv() {
    var cipher = new AppleTokenCipher(props);
    String a = cipher.encrypt("credential");
    String b = cipher.encrypt("credential");
    assertNotEquals(a, b);
    assertEquals("credential", cipher.decrypt(a));
    var parts = a.split("[.]");
    byte[] data = Base64.getDecoder().decode(parts[1]);
    data[0] ^= 1;
    String tampered = parts[0] + "." + Base64.getEncoder().encodeToString(data);
    assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
  }
}
