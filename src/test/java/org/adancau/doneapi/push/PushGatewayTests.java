package org.adancau.doneapi.push;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.ECGenParameterSpec;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class PushGatewayTests {
  final Clock clock = Clock.fixed(Instant.parse("2026-10-11T10:00:00Z"), ZoneOffset.UTC);

  KeyPair key(String curve) throws Exception {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec(curve));
    return generator.generateKeyPair();
  }

  String pem(KeyPair pair) {
    return "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----\n";
  }

  ApnsGateway gateway(String path, String encoded) {
    return new ApnsGateway(
        new PushProperties(true, "TEAM123456", "KEY1234567", path, encoded, "ro.done.app"),
        JsonMapper.builder().build(),
        clock);
  }

  @Test
  void disabledRequiresNoCredentialsAndSendsNothing() {
    var g =
        new ApnsGateway(
            new PushProperties(false, "", "", "", "", "ro.done.app"),
            JsonMapper.builder().build(),
            clock);
    assertFalse(g.enabled());
    assertFalse(g.send("sandbox", "a".repeat(64), Map.of(), "test").delivered());
  }

  @Test
  void base64KeySignsVerifiableAppleProviderToken() throws Exception {
    var pair = key("secp256r1");
    var g =
        gateway(
            "",
            Base64.getEncoder()
                .encodeToString(pem(pair).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    var jwt = SignedJWT.parse(g.authorization());
    assertTrue(jwt.verify(new ECDSAVerifier((ECPublicKey) pair.getPublic())));
    assertEquals("ES256", jwt.getHeader().getAlgorithm().getName());
    assertEquals("KEY1234567", jwt.getHeader().getKeyID());
    assertEquals("TEAM123456", jwt.getJWTClaimsSet().getIssuer());
    assertEquals(clock.instant(), jwt.getJWTClaimsSet().getIssueTime().toInstant());
    assertEquals(g.authorization(), g.authorization());
  }

  @Test
  void privateKeyFileIsSupported(@TempDir Path directory) throws Exception {
    var path = directory.resolve("PushKey.p8");
    Files.writeString(path, pem(key("secp256r1")));
    assertTrue(gateway(path.toString(), "").enabled());
  }

  @Test
  void missingAndMalformedCredentialsFailBeforeStartup() {
    assertThrows(IllegalStateException.class, () -> gateway("/path/does/not/exist", ""));
    assertThrows(IllegalStateException.class, () -> gateway("", "invalid-base64!"));
  }

  @Test
  void wrongCurveIsRejected() throws Exception {
    var pair = key("secp384r1");
    assertThrows(
        IllegalStateException.class,
        () ->
            gateway(
                "",
                Base64.getEncoder()
                    .encodeToString(pem(pair).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
  }
}
