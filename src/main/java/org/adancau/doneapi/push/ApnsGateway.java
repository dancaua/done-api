package org.adancau.doneapi.push;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ApnsGateway implements PushGateway {
  private final PushProperties props;
  private final JsonMapper json;
  private final Clock clock;
  private final ECPrivateKey key;
  private final HttpClient http;
  private String bearer;
  private Instant signedAt = Instant.EPOCH;

  public ApnsGateway(PushProperties p, JsonMapper j, Clock c) {
    props = p;
    json = j;
    clock = c;
    http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    if (!p.enabled()) {
      key = null;
      return;
    }
    try {
      if (p.teamId() == null
          || p.teamId().isBlank()
          || p.keyId() == null
          || p.keyId().isBlank()
          || p.topic() == null
          || p.topic().isBlank()) throw new IllegalArgumentException();
      String pem =
          (p.privateKeyBase64() != null && !p.privateKeyBase64().isBlank()
                  ? new String(
                      Base64.getDecoder().decode(p.privateKeyBase64()),
                      java.nio.charset.StandardCharsets.UTF_8)
                  : Files.readString(Path.of(p.privateKeyPath())))
              .replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      key =
          (ECPrivateKey)
              KeyFactory.getInstance("EC")
                  .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
      if (key.getParams().getCurve().getField().getFieldSize() != 256)
        throw new IllegalArgumentException("APNs requires an ES256 key.");
    } catch (Exception e) {
      throw new IllegalStateException(
          "APNs requires APNS_TEAM_ID, APNS_KEY_ID, APNS_PRIVATE_KEY_PATH (or"
              + " APNS_PRIVATE_KEY_BASE64) and APNS_TOPIC with a valid .p8 key.",
          e);
    }
  }

  public boolean enabled() {
    return props.enabled();
  }

  synchronized String authorization() throws JOSEException {
    if (bearer == null || clock.instant().isAfter(signedAt.plusSeconds(2700))) {
      var jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(props.keyId()).build(),
              new JWTClaimsSet.Builder()
                  .issuer(props.teamId())
                  .issueTime(Date.from(clock.instant()))
                  .build());
      jwt.sign(new ECDSASigner(key));
      bearer = jwt.serialize();
      signedAt = clock.instant();
    }
    return bearer;
  }

  public Result send(
      String environment, String token, Map<String, Object> payload, String collapseId) {
    if (!enabled()) return new Result(false, false, false);
    try {
      String host =
          environment.equals("sandbox") ? "api.sandbox.push.apple.com" : "api.push.apple.com";
      var request =
          HttpRequest.newBuilder(URI.create("https://" + host + "/3/device/" + token))
              .timeout(Duration.ofSeconds(15))
              .header("authorization", "bearer " + authorization())
              .header("apns-topic", props.topic())
              .header("apns-push-type", "alert")
              .header("apns-priority", "10")
              .header("apns-collapse-id", collapseId)
              .header(
                  "apns-expiration",
                  String.valueOf(clock.instant().plusSeconds(300).getEpochSecond()))
              .header("content-type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      String reason =
          response.body().isBlank() ? "" : json.readTree(response.body()).path("reason").asText();
      return new Result(
          status == 200,
          status == 410
              || Set.of("BadDeviceToken", "DeviceTokenNotForTopic", "Unregistered")
                  .contains(reason),
          status == 429 || status >= 500 || status == 403);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(false, false, true);
    } catch (Exception e) {
      return new Result(false, false, true);
    }
  }
}
