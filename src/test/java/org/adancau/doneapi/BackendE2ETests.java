package org.adancau.doneapi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.adancau.doneapi.auth.*;
import org.adancau.doneapi.persistence.*;
import org.adancau.doneapi.session.SessionEventProcessor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.jobs.enabled=false",
      "app.rate.enabled=false",
      "app.rate.concurrent-auth=16",
      "app.recovery.enabled=true",
      "app.recovery.delivery-enabled=false",
      "app.recovery.from=security@done.test",
      "app.apple.enabled=true",
      "app.apple.encryption-key=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=",
      "app.auth.secret=YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXphYmNkZWY=",
      "logging.level.root=WARN"
    })
@Import(BackendE2ETests.TestTime.class)
class BackendE2ETests {
  private static PostgreSQLContainer postgres;
  private static final TestSmtpServer smtp=new TestSmtpServer();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry p) {
    p.add("spring.mail.host",() -> "localhost");
    p.add("spring.mail.port",smtp::port);
    String external = System.getenv("TEST_DATABASE_URL");
    if (external != null && !external.isBlank()) {
      p.add("spring.datasource.url", () -> external);
      p.add(
          "spring.datasource.username",
          () -> System.getenv().getOrDefault("TEST_DATABASE_USER", "done_test"));
      p.add(
          "spring.datasource.password",
          () -> System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
    } else {
      if (postgres==null) { postgres = new PostgreSQLContainer("postgres:16-alpine"); postgres.start(); }
      p.add("spring.datasource.url", postgres::getJdbcUrl);
      p.add("spring.datasource.username", postgres::getUsername);
      p.add("spring.datasource.password", postgres::getPassword);
    }
  }

  @TestConfiguration
  static class TestTime {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {
    volatile Instant now = Instant.parse("2026-10-05T12:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    public Instant instant() {
      return now;
    }

    void advance(long seconds) {
      now = now.plusSeconds(seconds);
    }
  }

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired MutableClock clock;
  @Autowired SessionEventProcessor processor;
  @Autowired AppleTokenCipher cipher;
  @Autowired RateLimits rates;
  @Autowired PasswordRecoveryService recovery;
  @Autowired org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;
  @MockitoBean AppleGateway apple;

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
  org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mapping;

  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  static final String PASSWORD = "Correct horse battery staple!";

  record Response(int status, JsonNode body) {}

  record User(String email, String token, String refresh, String id) {}

  @BeforeEach
  void resetState() {
    jdbc.execute("TRUNCATE app_users,apple_challenges,auth_rate_buckets,session_shares,recovery_mail_queue CASCADE");
    clock.now = Instant.parse("2026-10-05T12:00:00Z");
    reset(apple);
    smtp.messages.clear();smtp.rejectNext=false;
  }

  Response call(String method, String path, Object body, String token, String key) {
    try {
      var b =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
              .timeout(Duration.ofSeconds(20));
      if (token != null) b.header("Authorization", "Bearer " + token);
      if (key != null) b.header("Idempotency-Key", key);
      b.header("Content-Type", "application/json");
      b.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      return new Response(
          r.statusCode(), r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body()));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  JsonNode expect(int expected, String method, String path, Object body, String token, String key) {
    var r = call(method, path, body, token, key);
    assertEquals(expected, r.status(), method + " " + path + ": " + r.body());
    return r.body();
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  User user() {
    String email = "user-" + UUID.randomUUID() + "@example.com";
    var r =
        expect(
            201,
            "POST",
            "/auth/register",
            Map.of("email", email, "password", PASSWORD, "displayName", "Alex"),
            null,
            null);
    return new User(
        email,
        r.get("accessToken").asText(),
        r.get("refreshToken").asText(),
        r.get("user").get("id").asText());
  }

  User renew(User u) {
    var r = expect(200, "POST", "/auth/refresh", Map.of("refreshToken", u.refresh()), null, null);
    return new User(
        u.email(), r.get("accessToken").asText(), r.get("refreshToken").asText(), u.id());
  }

  JsonNode device(User u, String kind) {
    return expect(201, "POST", "/appliances", Map.of("kind", kind), u.token(), key());
  }

  JsonNode start(User u, JsonNode device, String name, int minutes) {
    return expect(
        201,
        "POST",
        "/appliances/" + device.get("id").asText() + "/sessions",
        Map.of("program", Map.of("name", name, "minutes", minutes)),
        u.token(),
        key());
  }

  JsonNode action(User u, JsonNode s, String action) {
    return expect(
        200, "POST", "/sessions/" + s.get("id").asText() + "/" + action, null, u.token(), key());
  }

  JsonNode challenge() {
    return expect(200, "POST", "/auth/apple/challenge", null, null, null);
  }

  Map<String, Object> appleRequest(JsonNode c) {
    return Map.of(
        "challengeId",
        c.get("challengeId").asText(),
        "identityToken",
        "signed-token",
        "authorizationCode",
        "one-use-code",
        "displayName",
        "Alex Apple");
  }

  @Test
  void registrationLoginValidationAndOwnerSnapshot() {
    var u = user();
    assertEquals(
        "Alex", expect(200, "GET", "/me", null, u.token(), null).get("displayName").asText());
    expect(401, "GET", "/state", null, null, null);
    expect(
        400,
        "POST",
        "/auth/register",
        Map.of("email", "invalid", "password", "short", "displayName", ""),
        null,
        null);
    expect(
        409,
        "POST",
        "/auth/register",
        Map.of(
            "email",
            u.email().toUpperCase(Locale.ROOT),
            "password",
            PASSWORD,
            "displayName",
            "Another"),
        null,
        null);
    expect(401, "POST", "/auth/login", Map.of("email", u.email(), "password", "wrong"), null, null);
    var login =
        expect(
            200,
            "POST",
            "/auth/login",
            Map.of("email", u.email().toUpperCase(Locale.ROOT), "password", PASSWORD),
            null,
            null);
    assertEquals(u.id(), login.get("user").get("id").asText());
    assertEquals(6, expect(200, "GET", "/catalog", null, u.token(), null).size());
    assertEquals(0, expect(200, "GET", "/state", null, u.token(), null).get("appliances").size());
  }

  @Test
  void countdownLifecycleHistoryStatisticsAndCollection() {
    var u = user();
    var d = device(u, "washer");
    assertEquals(3, d.get("programs").size());
    var s = start(u, d, "Rapid", 20);
    clock.advance(600);
    processor.processDue();
    processor.processDue();
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM activity_events WHERE kind='halfway'", Long.class));
    clock.advance(300);
    processor.processDue();
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM activity_events WHERE kind='five_minutes'", Long.class));
    clock.advance(301);
    processor.processDue();
    u = renew(u);
    var due = expect(200, "GET", "/sessions/" + s.get("id").asText(), null, u.token(), null);
    assertEquals("due", due.get("status").asText());
    assertTrue(due.get("completedAt").isNull());
    expect(
        409,
        "POST",
        "/appliances/" + d.get("id").asText() + "/sessions",
        Map.of("program", Map.of("name", "Second", "minutes", 5)),
        u.token(),
        key());
    var done = action(u, s, "complete");
    assertEquals("done", done.get("status").asText());
    assertEquals(1201, done.get("elapsedSeconds").asLong());
    clock.advance(1800);
    processor.processDue();
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM activity_events WHERE kind='reminder30'", Long.class));
    u = renew(u);
    action(u, s, "collect");
    clock.advance(7200);
    processor.processDue();
    u = renew(u);
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM activity_events WHERE kind='reminder120'", Long.class));
    var history =
        expect(
            200, "GET", "/appliances/" + d.get("id").asText() + "/sessions", null, u.token(), null);
    assertEquals("collected", history.get("items").get(0).get("status").asText());
    var stats = expect(200, "GET", "/statistics?period=week", null, u.token(), null);
    assertEquals(1, stats.get("completedSessions").asLong());
    assertEquals(1201, stats.get("averageDurationSeconds").asLong());
    assertEquals(7, stats.get("usagePerDay").size());
  }

  @Test
  void allFiveKindsAndCookingReadyImmediately() {
    var u = user();
    for (String kind : List.of("washer", "dryer", "dishwasher", "oven", "hob")) {
      var d = device(u, kind);
      var program = d.get("programs").get(0);
      var s =
          expect(
              201,
              "POST",
              "/appliances/" + d.get("id").asText() + "/sessions",
              Map.of("programId", program.get("id").asText()),
              u.token(),
              key());
      clock.advance(30);
      var done = action(u, s, "complete");
      if (kind.equals("oven") || kind.equals("hob")) {
        assertEquals("collected", done.get("status").asText());
        assertEquals(
            "idle",
            expect(200, "GET", "/appliances/" + d.get("id").asText(), null, u.token(), null)
                .get("status")
                .asText());
      } else {
        assertEquals("done", done.get("status").asText());
        action(u, s, "collect");
      }
    }
    assertEquals(
        5,
        expect(200, "GET", "/statistics", null, u.token(), null).get("completedSessions").asLong());
  }

  @Test
  void stopwatchHasNoExpiryOrMilestoneAndKeepsExactElapsed() {
    var u = user();
    var d = device(u, "hob");
    var s =
        expect(
            201,
            "POST",
            "/appliances/" + d.get("id").asText() + "/sessions",
            Map.of("mode", "stopwatch", "program", Map.of("name", "Paste", "minutes", 0)),
            u.token(),
            key());
    assertTrue(s.get("expectedEnd").isNull());
    clock.advance(86400 * 7 + 12);
    processor.processDue();
    u = renew(u);
    var current = expect(200, "GET", "/sessions/" + s.get("id").asText(), null, u.token(), null);
    assertEquals("running", current.get("status").asText());
    assertEquals(86400 * 7 + 12, current.get("elapsedSeconds").asLong());
    expect(
        400,
        "POST",
        "/sessions/" + s.get("id").asText() + "/extend",
        Map.of("minutes", 10),
        u.token(),
        key());
    assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM activity_events", Long.class));
    var done = action(u, s, "complete");
    assertEquals(86400 * 7 + 12, done.get("elapsedSeconds").asLong());
    assertEquals("collected", done.get("status").asText());
    assertEquals(
        3,
        expect(200, "GET", "/appliances/" + d.get("id").asText(), null, u.token(), null)
            .get("programs")
            .size());
  }

  @Test
  void stopwatchWorksForOtherKindsAndCannotHaveMinutes() {
    var u = user();
    var d = device(u, "oven");
    expect(
        201,
        "POST",
        "/appliances/" + d.get("id").asText() + "/sessions",
        Map.of("mode", "stopwatch"),
        u.token(),
        key());
    var hob = device(u, "hob");
    expect(
        400,
        "POST",
        "/appliances/" + hob.get("id").asText() + "/sessions",
        Map.of("mode", "stopwatch", "program", Map.of("name", "Test", "minutes", 1)),
        u.token(),
        key());
  }

  @Test
  void idempotencyIsExactAndRejectsChangedPayloadAndMissingKey() {
    var u = user();
    String command = key();
    var payload = Map.of("kind", "oven", "name", "Bucătărie");
    var a = expect(201, "POST", "/appliances", payload, u.token(), command);
    var repeated = expect(201, "POST", "/appliances", payload, u.token(), command);
    assertEquals(a, repeated);
    expect(
        409, "POST", "/appliances", Map.of("kind", "oven", "name", "Alt nume"), u.token(), command);
    expect(400, "POST", "/appliances", payload, u.token(), null);
    assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM appliances", Long.class));
  }

  @Test
  void concurrentRetriesCreateExactlyOneSession() throws Exception {
    var u = user();
    var d = device(u, "washer");
    String command = key();
    var payload = Map.of("programId", d.get("programs").get(0).get("id").asText());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<Future<Response>>();
      for (int i = 0; i < 12; i++)
        tasks.add(
            executor.submit(
                () ->
                    call(
                        "POST",
                        "/appliances/" + d.get("id").asText() + "/sessions",
                        payload,
                        u.token(),
                        command)));
      var ids = new HashSet<String>();
      for (var f : tasks) {
        var res = f.get(20, TimeUnit.SECONDS);
        assertEquals(201, res.status(), res.body().toString());
        ids.add(res.body().get("id").asText());
      }
      assertEquals(1, ids.size());
    }
    assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM appliance_sessions", Long.class));
    assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM activity_events", Long.class));
  }

  @Test
  void simultaneousDifferentCommandsCannotOpenTwoSessions() throws Exception {
    var u = user();
    var d = device(u, "washer");
    var payload = Map.of("programId", d.get("programs").get(0).get("id").asText());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  call(
                      "POST",
                      "/appliances/" + d.get("id").asText() + "/sessions",
                      payload,
                      u.token(),
                      key()));
      var second =
          executor.submit(
              () ->
                  call(
                      "POST",
                      "/appliances/" + d.get("id").asText() + "/sessions",
                      payload,
                      u.token(),
                      key()));
      assertEquals(Set.of(201, 409), Set.of(first.get().status(), second.get().status()));
    }
    assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM appliance_sessions", Long.class));
  }

  @Test
  void ownersCannotReadOrMutateOtherUsersResources() {
    var owner = user();
    var intruder = user();
    var d = device(owner, "washer");
    var s = start(owner, d, "Secret", 20);
    String id = d.get("id").asText();
    expect(404, "GET", "/appliances/" + id, null, intruder.token(), null);
    expect(404, "DELETE", "/appliances/" + id, null, intruder.token(), key());
    expect(
        404,
        "POST",
        "/sessions/" + s.get("id").asText() + "/cancel",
        null,
        intruder.token(),
        key());
    expect(404, "GET", "/appliances/" + id + "/sessions", null, intruder.token(), null);
    expect(
        404,
        "PATCH",
        "/appliances/" + id + "/programs/" + d.get("programs").get(0).get("id").asText(),
        Map.of("name", "Hack", "minutes", 10, "version", 0),
        intruder.token(),
        key());
    var event = expect(200, "GET", "/activity", null, owner.token(), null).get("items").get(0);
    expect(
        404,
        "POST",
        "/activity/" + event.get("id").asText() + "/read",
        null,
        intruder.token(),
        key());
    assertEquals(
        0, expect(200, "GET", "/state", null, intruder.token(), null).get("appliances").size());
    assertEquals(
        0, expect(200, "GET", "/me/export", null, intruder.token(), null).get("sessions").size());
  }

  @Test
  void deletingActiveDeviceCascadesHistoryAndKeepsOtherDevice() {
    var u = user();
    var a = device(u, "washer");
    var b = device(u, "oven");
    var sa = start(u, a, "Test", 20);
    start(u, b, "Coacere", 10);
    String id = a.get("id").asText();
    String command = key();
    var response = expect(200, "DELETE", "/appliances/" + id, null, u.token(), command);
    assertEquals(response, expect(200, "DELETE", "/appliances/" + id, null, u.token(), command));
    expect(404, "GET", "/sessions/" + sa.get("id").asText(), null, u.token(), null);
    assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM appliance_sessions", Long.class));
    assertEquals(4L, jdbc.queryForObject("SELECT count(*) FROM programs", Long.class));
    assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM activity_events", Long.class));
    assertEquals(
        b.get("id"),
        expect(200, "GET", "/state", null, u.token(), null).get("appliances").get(0).get("id"));
  }

  @Test
  void cancelIsInHistoryAndExcludedFromStatistics() {
    var u = user();
    var d = device(u, "dishwasher");
    var s = start(u, d, "Rapid", 20);
    clock.advance(42);
    action(u, s, "cancel");
    var history =
        expect(
                200,
                "GET",
                "/appliances/" + d.get("id").asText() + "/sessions",
                null,
                u.token(),
                null)
            .get("items")
            .get(0);
    assertEquals("canceled", history.get("status").asText());
    assertEquals(42, history.get("elapsedSeconds").asLong());
    assertEquals(
        0,
        expect(200, "GET", "/statistics", null, u.token(), null).get("completedSessions").asLong());
    expect(409, "POST", "/sessions/" + s.get("id").asText() + "/complete", null, u.token(), key());
    start(u, d, "Nou", 5);
  }

  @Test
  void extensionAfterDueMovesDeadlineAndKeepsOriginalHalfway() {
    var u = user();
    var d = device(u, "washer");
    var s = start(u, d, "Rapid", 10);
    clock.advance(600);
    processor.processDue();
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM activity_events WHERE kind='five_minutes'", Long.class));
    var extended =
        expect(
            200,
            "POST",
            "/sessions/" + s.get("id").asText() + "/extend",
            Map.of("minutes", 10),
            u.token(),
            key());
    assertEquals(clock.instant().plusSeconds(600).toString(), extended.get("expectedEnd").asText());
    clock.advance(300);
    processor.processDue();
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM activity_events WHERE kind='halfway'", Long.class));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM activity_events WHERE kind='five_minutes'", Long.class));
    expect(
        400,
        "POST",
        "/sessions/" + s.get("id").asText() + "/extend",
        Map.of("minutes", 0),
        u.token(),
        key());
  }

  @Test
  void programEditingKeepsHistoricalSnapshotAndOptimisticVersion() {
    var u = user();
    var d = device(u, "oven");
    String id = d.get("id").asText();
    var p = d.get("programs").get(0);
    var s =
        expect(
            201,
            "POST",
            "/appliances/" + id + "/sessions",
            Map.of("programId", p.get("id").asText()),
            u.token(),
            key());
    var edited =
        expect(
            200,
            "PATCH",
            "/appliances/" + id + "/programs/" + p.get("id").asText(),
            Map.of("name", "Nou", "minutes", 20, "version", 0),
            u.token(),
            key());
    assertEquals(1, edited.get("version").asLong());
    expect(
        409,
        "PATCH",
        "/appliances/" + id + "/programs/" + p.get("id").asText(),
        Map.of("name", "Vechi", "minutes", 10, "version", 0),
        u.token(),
        key());
    expect(
        200,
        "DELETE",
        "/appliances/" + id + "/programs/" + p.get("id").asText(),
        null,
        u.token(),
        key());
    var stored = expect(200, "GET", "/sessions/" + s.get("id").asText(), null, u.token(), null);
    assertEquals("Încălzire", stored.get("programName").asText());
    assertEquals(10, stored.get("minutes").asInt());
    assertTrue(stored.get("programId").isNull());
    expect(
        200,
        "PATCH",
        "/appliances/" + id,
        Map.of("name", "Cuptor nou", "version", 0),
        u.token(),
        key());
    expect(
        409, "PATCH", "/appliances/" + id, Map.of("name", "Stale", "version", 0), u.token(), key());
  }

  @Test
  void preferredLanguageIsValidatedPersistedAndIncludedInStateAndAuth() {
    var u = user();
    var original = expect(200, "GET", "/me", null, u.token(), null);
    assertEquals("en", original.get("language").asText());
    for (String language : List.of("en", "ro", "es", "it", "fr", "de", "pl", "hi", "ja")) {
      String mutation = key();
      var first = expect(200, "PATCH", "/me", Map.of("language", language), u.token(), mutation);
      var retry = expect(200, "PATCH", "/me", Map.of("language", language), u.token(), mutation);
      assertEquals(first, retry);
      assertEquals(language, first.get("language").asText());
      assertEquals(original.get("timezone"), first.get("timezone"));
      assertEquals(original.get("displayName"), first.get("displayName"));
      assertEquals(
          language,
          expect(200, "GET", "/state", null, u.token(), null).get("user").get("language").asText());
    }
    for (String bad : List.of("", "eng", "en-US", "ru", "JA")) {
      expect(400, "PATCH", "/me", Map.of("language", bad), u.token(), key());
    }
    expect(200, "PATCH", "/me", Map.of("displayName", "Other name"), u.token(), key());
    assertEquals("ja", expect(200, "GET", "/me", null, u.token(), null).get("language").asText());
    var login =
        expect(
            200,
            "POST",
            "/auth/login",
            Map.of("email", u.email(), "password", PASSWORD),
            null,
            null);
    assertEquals("ja", login.get("user").get("language").asText());
    var other = user();
    assertEquals(
        "en", expect(200, "GET", "/me", null, other.token(), null).get("language").asText());
  }

  @Test
  void legacyProfileRetriesKeepTheirHashAndDecodeCachedResponsesWithoutLanguage() {
    var u = user();
    expect(200, "PATCH", "/me", Map.of("language", "ro"), u.token(), key());
    String mutation = key();
    var first =
        expect(200, "PATCH", "/me", Map.of("displayName", "Legacy name"), u.token(), mutation);
    var legacyPayload = new LinkedHashMap<String, Object>();
    legacyPayload.put("displayName", "Legacy name");
    legacyPayload.put("timezone", null);
    legacyPayload.put("notificationsEnabled", null);
    String legacyHash =
        org.adancau.doneapi.common.Crypto.hash(
            "account.update:" + json.writeValueAsString(legacyPayload));
    assertEquals(
        legacyHash,
        jdbc.queryForObject(
            "SELECT request_hash FROM mutation_receipts WHERE user_id = ? AND request_id = ?",
            String.class,
            UUID.fromString(u.id()),
            UUID.fromString(mutation)));
    jdbc.update(
        "UPDATE mutation_receipts SET response_json = (response_json::jsonb - 'language')::text"
            + " WHERE user_id = ? AND request_id = ?",
        UUID.fromString(u.id()),
        UUID.fromString(mutation));
    expect(200, "PATCH", "/me", Map.of("displayName", "New name"), u.token(), key());
    var replay =
        expect(200, "PATCH", "/me", Map.of("displayName", "Legacy name"), u.token(), mutation);
    assertEquals(first, replay);
    assertEquals("ro", replay.get("language").asText());
    assertEquals(
        "New name", expect(200, "GET", "/me", null, u.token(), null).get("displayName").asText());
  }

  @Test
  void registrationAcceptsOnboardingLanguageAndRejectsUnsupportedCodes() {
    String email = "language-" + UUID.randomUUID() + "@example.com";
    var valid =
        expect(
            201,
            "POST",
            "/auth/register",
            Map.of("email", email, "password", PASSWORD, "displayName", "Alex", "language", "hi"),
            null,
            null);
    assertEquals("hi", valid.get("user").get("language").asText());
    expect(
        400,
        "POST",
        "/auth/register",
        Map.of(
            "email",
            "invalid-" + email,
            "password",
            PASSWORD,
            "displayName",
            "Alex",
            "language",
            "unsupported"),
        null,
        null);
  }

  @Test
  void activityReadProfileTimezoneExportAndPagination() {
    var u = user();
    var d = device(u, "hob");
    start(u, d, "Sos", 10);
    var profile =
        expect(
            200,
            "PATCH",
            "/me",
            Map.of(
                "displayName",
                "Alex Popescu",
                "timezone",
                "America/New_York",
                "notificationsEnabled",
                false),
            u.token(),
            key());
    assertFalse(profile.get("notificationsEnabled").asBoolean());
    expect(
        400,
        "PATCH",
        "/me",
        Map.of("displayName", "Alex", "timezone", "Invalid/Zone", "notificationsEnabled", true),
        u.token(),
        key());
    var events = expect(200, "GET", "/activity", null, u.token(), null);
    var id = events.get("items").get(0).get("id").asText();
    expect(200, "POST", "/activity/" + id + "/read", null, u.token(), key());
    expect(200, "POST", "/activity/read-all", null, u.token(), key());
    assertEquals(
        0, expect(200, "GET", "/state", null, u.token(), null).get("unreadActivityCount").asLong());
    expect(400, "GET", "/activity?size=101", null, u.token(), null);
    expect(400, "GET", "/activity?page=-1", null, u.token(), null);
    var export = expect(200, "GET", "/me/export", null, u.token(), null);
    assertEquals(1, export.get("sessions").size());
    assertEquals(1, export.get("appliances").size());
    assertFalse(export.toString().contains("passwordHash"));
  }

  @Test
  void refreshRotationReplayRevokesWholeFamilyAndAccess() {
    var u = user();
    var refreshed =
        expect(200, "POST", "/auth/refresh", Map.of("refreshToken", u.refresh()), null, null);
    String access = refreshed.get("accessToken").asText();
    expect(200, "GET", "/me", null, access, null);
    expect(401, "POST", "/auth/refresh", Map.of("refreshToken", u.refresh()), null, null);
    expect(401, "GET", "/me", null, access, null);
    expect(401, "GET", "/me", null, u.token(), null);
    expect(
        401,
        "POST",
        "/auth/refresh",
        Map.of("refreshToken", refreshed.get("refreshToken").asText()),
        null,
        null);
  }

  @Test
  void logoutAndPasswordChangeRevokeTokensImmediately() {
    var u = user();
    var second =
        expect(
            200,
            "POST",
            "/auth/login",
            Map.of("email", u.email(), "password", PASSWORD),
            null,
            null);
    expect(204, "POST", "/auth/logout", null, u.token(), null);
    expect(401, "GET", "/me", null, u.token(), null);
    String token = second.get("accessToken").asText();
    expect(200, "GET", "/me", null, token, null);
    expect(
        204,
        "POST",
        "/me/password",
        Map.of("currentPassword", PASSWORD, "newPassword", "Another strong password!"),
        token,
        null);
    expect(401, "GET", "/me", null, token, null);
    expect(
        401, "POST", "/auth/login", Map.of("email", u.email(), "password", PASSWORD), null, null);
    expect(
        200,
        "POST",
        "/auth/login",
        Map.of("email", u.email(), "password", "Another strong password!"),
        null,
        null);
  }

  @Test
  void accountDeleteRemovesAllDataAndRevokesAccess() {
    var u = user();
    var d = device(u, "washer");
    start(u, d, "Test", 20);
    expect(401, "DELETE", "/me", Map.of("password", "wrong"), u.token(), null);
    expect(204, "DELETE", "/me", Map.of("password", PASSWORD), u.token(), null);
    expect(401, "GET", "/state", null, u.token(), null);
    expect(401, "POST", "/auth/refresh", Map.of("refreshToken", u.refresh()), null, null);
    for (String table :
        List.of(
            "app_users",
            "auth_sessions",
            "refresh_tokens",
            "appliances",
            "programs",
            "appliance_sessions",
            "activity_events",
            "mutation_receipts"))
      assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class), table);
  }

  @Test
  void accessExpiresButValidRefreshCanRenewAndExpiredFamilyCannot() {
    var u = user();
    clock.advance(920);
    expect(401, "GET", "/me", null, u.token(), null);
    var rotated =
        expect(200, "POST", "/auth/refresh", Map.of("refreshToken", u.refresh()), null, null);
    expect(200, "GET", "/me", null, rotated.get("accessToken").asText(), null);
    clock.advance(86400 * 30L);
    expect(
        401,
        "POST",
        "/auth/refresh",
        Map.of("refreshToken", rotated.get("refreshToken").asText()),
        null,
        null);
  }

  @Test
  void appleUsesOnboardingLanguageForNewAccountsAndPreservesExistingPreference() {
    when(apple.authenticate(any(), anyString()))
        .thenReturn(new AppleGateway.Identity("language-apple-sub", null, "apple-refresh"));
    var c = challenge();
    var request = new HashMap<>(appleRequest(c));
    request.remove("displayName");
    request.put("language", "unsupported");
    expect(400, "POST", "/auth/apple", request, null, null);
    verifyNoInteractions(apple);
    request.put("language", "ja");
    var first = expect(200, "POST", "/auth/apple", request, null, null);
    assertEquals("ja", first.get("user").get("language").asText());
    assertEquals("DONE.", first.get("user").get("displayName").asText());
    var returning = new HashMap<>(appleRequest(challenge()));
    returning.put("language", "de");
    var second = expect(200, "POST", "/auth/apple", returning, null, null);
    assertEquals(first.get("user").get("id"), second.get("user").get("id"));
    assertEquals("ja", second.get("user").get("language").asText());
  }

  @Test
  void appleLoginConsumesNonceAndStoredCredentialIsEncryptedAndRevokedOnDelete() {
    when(apple.authenticate(any(), anyString()))
        .thenReturn(
            new AppleGateway.Identity("apple-sub", "apple@example.com", "apple-refresh-secret"));
    var c = challenge();
    var login = expect(200, "POST", "/auth/apple", appleRequest(c), null, null);
    String token = login.get("accessToken").asText();
    assertTrue(login.get("user").get("appleEnabled").asBoolean());
    assertFalse(login.get("user").get("passwordEnabled").asBoolean());
    String encrypted =
        jdbc.queryForObject("SELECT encrypted_refresh_token FROM apple_identities", String.class);
    assertFalse(encrypted.contains("apple-refresh-secret"));
    assertEquals("apple-refresh-secret", cipher.decrypt(encrypted));
    expect(401, "POST", "/auth/apple", appleRequest(c), null, null);
    verify(apple, times(1)).authenticate(any(), eq(c.get("nonce").asText()));
    expect(204, "DELETE", "/me", null, token, null);
    verify(apple).revoke("apple-refresh-secret");
    assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM apple_identities", Long.class));
  }

  @Test
  void appleDoesNotMergeAccountsByEmailAndCanBeLinkedExplicitly() {
    var u = user();
    when(apple.authenticate(any(), anyString()))
        .thenReturn(new AppleGateway.Identity("apple-sub", u.email(), "apple-refresh"));
    expect(409, "POST", "/auth/apple", appleRequest(challenge()), null, null);
    assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM app_users", Long.class));
    expect(200, "POST", "/me/identities/apple", appleRequest(challenge()), u.token(), null);
    var login = expect(200, "POST", "/auth/apple", appleRequest(challenge()), null, null);
    assertEquals(u.id(), login.get("user").get("id").asText());
    var other = user();
    expect(409, "POST", "/me/identities/apple", appleRequest(challenge()), other.token(), null);
  }

  @Test
  void expiredAppleNonceAndOldAppleSessionRequireNewSignin() {
    when(apple.authenticate(any(), anyString()))
        .thenReturn(new AppleGateway.Identity("apple-sub", null, "apple-refresh"));
    var c = challenge();
    clock.advance(301);
    expect(401, "POST", "/auth/apple", appleRequest(c), null, null);
    verifyNoInteractions(apple);
    var login = expect(200, "POST", "/auth/apple", appleRequest(challenge()), null, null);
    clock.advance(301);
    expect(409, "DELETE", "/me", null, login.get("accessToken").asText(), null);
    assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM app_users", Long.class));
  }

  @Test
  void loginLimitsPersistFailuresAndResetAfterWindow() {
    var u = user();
    for (int i = 0; i < 10; i++)
      expect(
          401, "POST", "/auth/login", Map.of("email", u.email(), "password", "wrong"), null, null);
    expect(
        429, "POST", "/auth/login", Map.of("email", u.email(), "password", PASSWORD), null, null);
    clock.advance(301);
    expect(
        200, "POST", "/auth/login", Map.of("email", u.email(), "password", PASSWORD), null, null);
    for (int i = 0; i < 3; i++) assertTrue(rates.consume("direct", 3));
    assertFalse(rates.consume("direct", 3));
  }

  @Test
  void databaseConstraintsRejectInvalidSessionEvenOutsideJpa() {
    var u = user();
    var d = device(u, "hob");
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO"
                    + " appliance_sessions(id,user_id,appliance_id,program_name,minutes,mode,started_at)"
                    + " VALUES (?,?,?,'Invalid',10,'countdown',?)",
                UUID.randomUUID(),
                UUID.fromString(u.id()),
                UUID.fromString(d.get("id").asText()),
                java.sql.Timestamp.from(clock.instant())));
    expect(
        400, "POST", "/appliances", Map.of("kind", "oven", "unexpected", true), u.token(), key());
  }

  @Test
  void logoutAllRevokesEveryDeviceSession() {
    var u = user();
    var other =
        expect(
            200,
            "POST",
            "/auth/login",
            Map.of("email", u.email(), "password", PASSWORD),
            null,
            null);
    expect(204, "POST", "/auth/logout-all", null, u.token(), null);
    expect(401, "GET", "/me", null, u.token(), null);
    expect(401, "GET", "/me", null, other.get("accessToken").asText(), null);
    expect(
        401,
        "POST",
        "/auth/refresh",
        Map.of("refreshToken", other.get("refreshToken").asText()),
        null,
        null);
  }

  @Test
  void timezoneDefinesCalendarPeriodsAndCompletedDay() {
    clock.now = Instant.parse("2026-10-05T03:59:00Z");
    var u = user();
    expect(
        200,
        "PATCH",
        "/me",
        Map.of("displayName", "Alex", "timezone", "America/New_York", "notificationsEnabled", true),
        u.token(),
        key());
    var d = device(u, "oven");
    var s = start(u, d, "Coacere", 30);
    clock.advance(30);
    action(u, s, "complete");
    var week = expect(200, "GET", "/statistics?period=week&date=2026-10-04", null, u.token(), null);
    assertEquals("2026-09-28", week.get("from").asText());
    assertEquals(1, week.get("completedSessions").asLong());
    assertEquals(1, week.get("usagePerDay").get(6).get("sessions").asLong());
    assertEquals(
        0,
        expect(200, "GET", "/statistics?period=week&date=2026-10-05", null, u.token(), null)
            .get("completedSessions")
            .asLong());
    assertEquals(
        1,
        expect(200, "GET", "/statistics?period=month", null, u.token(), null)
            .get("completedSessions")
            .asLong());
    assertEquals(
        1,
        expect(200, "GET", "/statistics?period=year", null, u.token(), null)
            .get("completedSessions")
            .asLong());
  }

  @Test
  void appleRevocationFailureKeepsAccountForRetry() {
    when(apple.authenticate(any(), anyString()))
        .thenReturn(new AppleGateway.Identity("apple-sub", null, "apple-refresh"));
    var login = expect(200, "POST", "/auth/apple", appleRequest(challenge()), null, null);
    doThrow(
            new org.adancau.doneapi.common.ApiException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "apple_unavailable",
                "Retry"))
        .when(apple)
        .revoke(anyString());
    String token = login.get("accessToken").asText();
    expect(503, "DELETE", "/me", null, token, null);
    expect(200, "GET", "/me", null, token, null);
    assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM apple_identities", Long.class));
  }

  @Test
  void chunkedOversizedBodyIsRejectedBeforeJsonParsing() throws Exception {
    byte[] bytes =
        json.writeValueAsBytes(
            Map.of("email", "x".repeat(70000), "password", PASSWORD, "displayName", "Alex"));
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/register"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(bytes)))
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(413, response.statusCode());
    assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM app_users", Long.class));
  }

  @Test
  void partialProfilePatchPreservesOtherPreferences() {
    var u = user();
    var updated = expect(200, "PATCH", "/me", Map.of("displayName", "Nume nou"), u.token(), key());
    assertTrue(updated.get("notificationsEnabled").asBoolean());
    assertEquals("Europe/Bucharest", updated.get("timezone").asText());
    updated = expect(200, "PATCH", "/me", Map.of("notificationsEnabled", false), u.token(), key());
    assertEquals("Nume nou", updated.get("displayName").asText());
    assertFalse(updated.get("notificationsEnabled").asBoolean());
    expect(400, "PATCH", "/me", Map.of(), u.token(), key());
  }

  @Test
  void openApiMatchesLiveRoutesAndDtoProperties() throws Exception {
    var spec =
        json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("docs/openapi.json")));
    for (var info : mapping.getHandlerMethods().keySet()) {
      for (String path : info.getPatternValues()) {
        if (!path.startsWith("/api/v1/")) continue;
        for (var method : info.getMethodsCondition().getMethods())
          assertNotNull(
              spec.get("paths").get(path).get(method.name().toLowerCase(Locale.ROOT)),
              path + " " + method);
      }
    }
    for (Class<?> group :
        List.of(
            AuthDtos.class,
            org.adancau.doneapi.account.AccountDtos.class,
            org.adancau.doneapi.appliance.ApplianceDtos.class,
            org.adancau.doneapi.session.SessionDtos.class,
            org.adancau.doneapi.activity.ActivityDtos.class,
            org.adancau.doneapi.sync.StateDtos.class,
            org.adancau.doneapi.household.HouseholdDtos.class,
            org.adancau.doneapi.sync.StatisticsService.class, org.adancau.doneapi.sharing.SharingDtos.class)) {
      for (Class<?> dto : group.getDeclaredClasses()) {
        if (!dto.isRecord()) continue;
        var props =
            spec.get("components").get("schemas").get(dto.getSimpleName()).get("properties");
        assertEquals(dto.getRecordComponents().length, props.size(), dto.getSimpleName());
        for (var component : dto.getRecordComponents())
          assertNotNull(
              props.get(component.getName()), dto.getSimpleName() + "." + component.getName());
      }
    }
  }

  @Test
  void householdsDefaultCreationCrudAndOwnerIsolation() {
    var u = user();
    var other = user();
    var homes = expect(200, "GET", "/households", null, u.token(), null);
    assertEquals(1, homes.size());
    assertEquals(u.id(), homes.get(0).get("id").asText());
    assertEquals("household.default", homes.get(0).get("localizationKey").asText());
    assertEquals(
        "last_household",
        expect(409, "DELETE", "/households/" + u.id(), null, u.token(), key())
            .get("code")
            .asText());
    var request = Map.of("name", "Holiday");
    String requestKey = key();
    var h = expect(201, "POST", "/households", request, u.token(), requestKey);
    assertEquals(h, expect(201, "POST", "/households", request, u.token(), requestKey));
    var id = h.get("id").asText();
    expect(404, "GET", "/households/" + id, null, other.token(), null);
    expect(
        404,
        "PATCH",
        "/households/" + id,
        Map.of("name", "Intrusion", "version", 0),
        other.token(),
        key());
    expect(404, "DELETE", "/households/" + id, null, other.token(), key());
    expect(404, "GET", "/appliances?householdId=" + id, null, other.token(), null);
    expect(404, "GET", "/activity?householdId=" + id, null, other.token(), null);
    expect(404, "GET", "/statistics?householdId=" + id, null, other.token(), null);
    var renamed =
        expect(
            200,
            "PATCH",
            "/households/" + id,
            Map.of("name", "Weekend", "version", h.get("version").asLong()),
            u.token(),
            key());
    assertEquals("Weekend", renamed.get("name").asText());
    assertTrue(renamed.get("localizationKey").isNull());
    expect(
        409,
        "PATCH",
        "/households/" + id,
        Map.of("name", "Old", "version", h.get("version").asLong()),
        u.token(),
        key());
    expect(409, "POST", "/households", Map.of("name", "weekend"), u.token(), key());
    expect(400, "POST", "/households", Map.of("name", "   "), u.token(), key());
    expect(400, "POST", "/households", Map.of("name", "x".repeat(61)), u.token(), key());
    assertEquals(2, expect(200, "GET", "/state", null, u.token(), null).get("households").size());
    assertEquals(
        2, expect(200, "GET", "/me/export", null, u.token(), null).get("households").size());
    String deletion = key();
    var deleted = expect(200, "DELETE", "/households/" + id, null, u.token(), deletion);
    assertEquals(deleted, expect(200, "DELETE", "/households/" + id, null, u.token(), deletion));
    assertEquals(1, expect(200, "GET", "/households", null, u.token(), null).size());
  }

  @Test
  void applianceNamesAreScopedToHomesAndMovesPreserveActiveHistory() {
    var u = user();
    var other = user();
    String home =
        expect(201, "POST", "/households", Map.of("name", "Holiday"), u.token(), key())
            .get("id")
            .asText();
    var first = device(u, "washer");
    assertEquals(u.id(), first.get("householdId").asText());
    var second =
        expect(
            201,
            "POST",
            "/appliances",
            Map.of("kind", "washer", "householdId", home),
            u.token(),
            key());
    expect(
        409,
        "POST",
        "/appliances",
        Map.of("kind", "washer", "householdId", home),
        u.token(),
        key());
    expect(
        404,
        "POST",
        "/appliances",
        Map.of("kind", "oven", "householdId", other.id()),
        u.token(),
        key());
    var session = start(u, first, "Cotton", 30);
    var sessionId = session.get("id").asText();
    String path = "/appliances/" + first.get("id").asText() + "/household";
    expect(
        409,
        "PATCH",
        path,
        Map.of("householdId", home, "version", first.get("version").asLong()),
        u.token(),
        key());
    expect(
        404,
        "PATCH",
        path,
        Map.of("householdId", other.id(), "version", first.get("version").asLong()),
        u.token(),
        key());
    expect(200, "DELETE", "/appliances/" + second.get("id").asText(), null, u.token(), key());
    String requestKey = key();
    var body = Map.of("householdId", home, "version", first.get("version").asLong());
    var moved = expect(200, "PATCH", path, body, u.token(), requestKey);
    assertEquals(moved, expect(200, "PATCH", path, body, u.token(), requestKey));
    assertEquals(sessionId, moved.get("activeSession").get("id").asText());
    assertEquals(session.get("expectedEnd"), moved.get("activeSession").get("expectedEnd"));
    assertEquals(
        0, expect(200, "GET", "/appliances?householdId=" + u.id(), null, u.token(), null).size());
    assertEquals(
        1, expect(200, "GET", "/appliances?householdId=" + home, null, u.token(), null).size());
    assertEquals(
        "household_not_empty",
        expect(409, "DELETE", "/households/" + home, null, u.token(), key()).get("code").asText());
    expect(
        409,
        "PATCH",
        path,
        Map.of("householdId", u.id(), "version", first.get("version").asLong()),
        u.token(),
        key());
    action(u, session, "complete");
    action(u, session, "collect");
    assertEquals(
        1,
        expect(200, "GET", "/statistics?householdId=" + home, null, u.token(), null)
            .get("completedSessions")
            .asLong());
    assertEquals(
        0,
        expect(200, "GET", "/statistics?householdId=" + u.id(), null, u.token(), null)
            .get("completedSessions")
            .asLong());
    assertEquals(
        0,
        expect(200, "GET", "/activity?householdId=" + u.id(), null, u.token(), null)
            .get("totalElements")
            .asLong());
    assertTrue(
        expect(200, "GET", "/activity?householdId=" + home, null, u.token(), null)
                .get("totalElements")
                .asLong()
            > 0);
  }

  @Test
  void householdReadAllDoesNotMarkOtherHomesRead() {
    var u = user();
    var first = device(u, "oven");
    start(u, first, "Bake", 20);
    String home =
        expect(201, "POST", "/households", Map.of("name", "Holiday"), u.token(), key())
            .get("id")
            .asText();
    var second =
        expect(
            201,
            "POST",
            "/appliances",
            Map.of("kind", "oven", "householdId", home),
            u.token(),
            key());
    start(u, second, "Bake", 20);
    expect(200, "POST", "/activity/read-all?householdId=" + home, null, u.token(), key());
    var firstEvents =
        expect(200, "GET", "/activity?householdId=" + u.id(), null, u.token(), null).get("items");
    var secondEvents =
        expect(200, "GET", "/activity?householdId=" + home, null, u.token(), null).get("items");
    assertTrue(firstEvents.get(0).get("readAt").isNull());
    assertFalse(secondEvents.get(0).get("readAt").isNull());
    assertEquals(
        1, expect(200, "GET", "/state", null, u.token(), null).get("unreadActivityCount").asLong());
  }

  @Test
  void householdOwnerConstraintBlocksCrossAccountSqlAttachment() {
    var u = user();
    var other = user();
    var a = device(u, "hob");
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE appliances SET household_id=? WHERE id=?",
                UUID.fromString(other.id()),
                UUID.fromString(a.get("id").asText())));
    assertEquals(
        u.id(),
        expect(200, "GET", "/appliances/" + a.get("id").asText(), null, u.token(), null)
            .get("householdId")
            .asText());
  }

  @Test
  void householdLimitIsEnforcedPerUser() {
    var u = user();
    for (int i = 1; i < 20; i++)
      expect(201, "POST", "/households", Map.of("name", "Home " + i), u.token(), key());
    assertEquals(
        "household_limit",
        expect(409, "POST", "/households", Map.of("name", "Overflow"), u.token(), key())
            .get("code")
            .asText());
    var other = user();
    expect(201, "POST", "/households", Map.of("name", "Home 1"), other.token(), key());
  }

  @Test
  void householdMigrationPreservesPreexistingAccountsAppliancesAndSessions() throws Exception {
    String schema = "legacy_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = jdbc.getDataSource().getConnection();
        var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + schema);
      try {
        connection.setSchema(schema);
        for (String file :
            List.of(
                "V1__accounts_and_sessions.sql",
                "V2__auth_rate_limits.sql",
                "V3__tenant_integrity.sql",
                "V4__preferred_language.sql")) {
          try (var input = getClass().getResourceAsStream("/db/migration/" + file)) {
            statement.execute(
                new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
          }
        }
        UUID userId = UUID.randomUUID(),
            applianceId = UUID.randomUUID(),
            sessionId = UUID.randomUUID();
        statement.execute(
            "INSERT INTO app_users(id,display_name,created_at) VALUES ('"
                + userId
                + "','Legacy',now())");
        statement.execute(
            "INSERT INTO appliances(id,user_id,name,kind,created_at) VALUES ('"
                + applianceId
                + "','"
                + userId
                + "','Washer','washer',now())");
        statement.execute(
            "INSERT INTO"
                + " appliance_sessions(id,user_id,appliance_id,program_name,minutes,mode,started_at,expected_end,completed_at)"
                + " VALUES ('"
                + sessionId
                + "','"
                + userId
                + "','"
                + applianceId
                + "','Cotton',30,'countdown',now()-interval '1 hour',now()-interval '30"
                + " minutes',now())");
        try (var input = getClass().getResourceAsStream("/db/migration/V5__households.sql")) {
          statement.execute(
              new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        statement.execute("UPDATE app_users SET notifications_enabled=false");
        try (var input =
            getClass().getResourceAsStream("/db/migration/V6__appliance_notifications.sql")) {
          statement.execute(
              new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        try (var result =
            statement.executeQuery(
                "SELECT a.household_id,h.user_id,s.id,a.notifications_enabled,(SELECT"
                    + " notifications_enabled FROM app_users WHERE id=a.user_id) FROM appliances a"
                    + " JOIN households h ON h.id=a.household_id JOIN appliance_sessions s ON"
                    + " s.appliance_id=a.id")) {
          assertTrue(result.next());
          assertEquals(userId, result.getObject(1, UUID.class));
          assertEquals(userId, result.getObject(2, UUID.class));
          assertEquals(sessionId, result.getObject(3, UUID.class));
          assertTrue(result.getBoolean(4));
          assertFalse(result.getBoolean(5));
          assertFalse(result.next());
        }
      } finally {
        connection.setSchema("public");
        statement.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }

  @Test
  void applianceNotificationPreferencesDefaultOnAndPreserveSessionsAcrossRenameAndMove() {
    var u = user();
    var a = device(u, "washer");
    var other = device(u, "oven");
    assertTrue(a.get("notificationsEnabled").asBoolean());
    var explicitlyMuted =
        expect(
            201,
            "POST",
            "/appliances",
            Map.of("kind", "dryer", "notificationsEnabled", false),
            u.token(),
            key());
    assertFalse(explicitlyMuted.get("notificationsEnabled").asBoolean());
    var session = start(u, a, "Cotton", 30);
    String id = a.get("id").asText();
    String mutation = key();
    var payload = Map.of("notificationsEnabled", false, "version", 0);
    var muted =
        expect(200, "PATCH", "/appliances/" + id + "/notifications", payload, u.token(), mutation);
    assertEquals(
        muted,
        expect(200, "PATCH", "/appliances/" + id + "/notifications", payload, u.token(), mutation));
    assertFalse(muted.get("notificationsEnabled").asBoolean());
    assertEquals(session.get("id"), muted.get("activeSession").get("id"));
    assertEquals(session.get("expectedEnd"), muted.get("activeSession").get("expectedEnd"));
    assertTrue(
        expect(200, "GET", "/appliances/" + other.get("id").asText(), null, u.token(), null)
            .get("notificationsEnabled")
            .asBoolean());
    var renamed =
        expect(
            200,
            "PATCH",
            "/appliances/" + id,
            Map.of("name", "Laundry room", "version", muted.get("version").asLong()),
            u.token(),
            key());
    assertFalse(renamed.get("notificationsEnabled").asBoolean());
    var home = expect(201, "POST", "/households", Map.of("name", "Holiday"), u.token(), key());
    var moved =
        expect(
            200,
            "PATCH",
            "/appliances/" + id + "/household",
            Map.of(
                "householdId", home.get("id").asText(), "version", renamed.get("version").asLong()),
            u.token(),
            key());
    assertFalse(moved.get("notificationsEnabled").asBoolean());
    assertEquals(session.get("id"), moved.get("activeSession").get("id"));
    clock.advance(901);
    processor.processDue();
    assertFalse(
        expect(
                200,
                "GET",
                "/activity?householdId=" + home.get("id").asText(),
                null,
                u.token(),
                null)
            .get("items")
            .isEmpty());
    // Muting transport preferences never suppresses the durable activity/history feed.
    for (String path : List.of("/state", "/me/export")) {
      var devices = expect(200, "GET", path, null, u.token(), null).get("appliances");
      boolean found = false;
      for (var d : devices)
        if (d.get("id").asText().equals(id)) {
          assertFalse(d.get("notificationsEnabled").asBoolean());
          found = true;
        }
      assertTrue(found);
    }
    var enabled =
        expect(
            200,
            "PATCH",
            "/appliances/" + id + "/notifications",
            Map.of("notificationsEnabled", true, "version", moved.get("version").asLong()),
            u.token(),
            key());
    assertTrue(enabled.get("notificationsEnabled").asBoolean());
    expect(200, "PATCH", "/me", Map.of("notificationsEnabled", false), u.token(), key());
    assertTrue(
        expect(200, "GET", "/appliances/" + id, null, u.token(), null)
            .get("notificationsEnabled")
            .asBoolean());
  }

  @Test
  void applianceNotificationWritesRequireOwnerVersionAndStableIdempotencyPayload() {
    var u = user();
    var other = user();
    var a = device(u, "washer");
    String route = "/appliances/" + a.get("id").asText() + "/notifications";
    expect(401, "PATCH", route, Map.of("notificationsEnabled", false, "version", 0), null, key());
    expect(
        404,
        "PATCH",
        route,
        Map.of("notificationsEnabled", false, "version", 0),
        other.token(),
        key());
    expect(
        404,
        "PATCH",
        "/appliances/" + UUID.randomUUID() + "/notifications",
        Map.of("notificationsEnabled", false, "version", 0),
        u.token(),
        key());
    expect(400, "PATCH", route, Map.of("version", 0), u.token(), key());
    expect(400, "PATCH", route, Map.of("notificationsEnabled", true), u.token(), key());
    expect(
        400, "PATCH", route, Map.of("notificationsEnabled", true, "version", -1), u.token(), key());
    expect(
        400,
        "PATCH",
        route,
        Map.of("notificationsEnabled", "invalid", "version", 0),
        u.token(),
        key());
    var nullable = new HashMap<String, Object>();
    nullable.put("notificationsEnabled", null);
    nullable.put("version", 0);
    expect(400, "PATCH", route, nullable, u.token(), key());
    String mutation = key();
    expect(
        200,
        "PATCH",
        route,
        Map.of("notificationsEnabled", false, "version", 0),
        u.token(),
        mutation);
    expect(
        409,
        "PATCH",
        route,
        Map.of("notificationsEnabled", true, "version", 0),
        u.token(),
        mutation);
    expect(
        409, "PATCH", route, Map.of("notificationsEnabled", true, "version", 0), u.token(), key());
    assertFalse(
        expect(200, "GET", "/appliances/" + a.get("id").asText(), null, u.token(), null)
            .get("notificationsEnabled")
            .asBoolean());
  }

  @Test
  void legacyApplianceCreationReceiptsKeepTheirHashAfterNotificationFieldIsAdded() {
    var u = user();
    String mutation = key();
    var request = Map.of("kind", "washer");
    var first = expect(201, "POST", "/appliances", request, u.token(), mutation);
    var legacy = new LinkedHashMap<String, Object>();
    legacy.put("kind", "washer");
    legacy.put("name", null);
    String hash =
        org.adancau.doneapi.common.Crypto.hash(
            "appliance.create:" + json.writeValueAsString(legacy));
    assertEquals(
        hash,
        jdbc.queryForObject(
            "SELECT request_hash FROM mutation_receipts WHERE user_id=? AND request_id=?",
            String.class,
            UUID.fromString(u.id()),
            UUID.fromString(mutation)));
    jdbc.update(
        "UPDATE mutation_receipts SET response_json=(response_json::jsonb -"
            + " 'notificationsEnabled')::text WHERE user_id=? AND request_id=?",
        UUID.fromString(u.id()),
        UUID.fromString(mutation));
    var retry = expect(201, "POST", "/appliances", request, u.token(), mutation);
    assertEquals(first, retry);
    var fresh =
        expect(200, "GET", "/appliances/" + first.get("id").asText(), null, u.token(), null);
    assertTrue(fresh.get("notificationsEnabled").asBoolean());
    assertEquals(1, expect(200, "GET", "/appliances", null, u.token(), null).size());
  }
  Response raw(String method,String path,Object body,String bearer) {
    try {
      var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
      if(bearer!=null)builder.header("Authorization","Bearer "+bearer);
      builder.header("Content-Type","application/json");
      builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(),response.body().isBlank()?json.createObjectNode():json.readTree(response.body()));
    }catch(Exception e){throw new AssertionError(e);}
  }
  String publicPath(JsonNode share) { return URI.create(share.get("shareUrl").asText()).getPath().replace("/share/","/api/shares/"); }
  Map<String,Object> localSnapshot() {
    var result=new LinkedHashMap<String,Object>();
    result.put("schemaVersion",1);result.put("id",UUID.randomUUID());result.put("applianceName","My 家 {0}");result.put("kind","custom");
    result.put("program",Map.of("name","Literal {0} 家","minutes",30,"isCustom",true));result.put("timingMode","countdown");
    result.put("startedAt",clock.instant().toString());result.put("expectedEnd",clock.instant().plusSeconds(1800).toString());result.put("capturedAt",clock.instant().toString());result.put("language","ja");return result;
  }

  @Test
  void everyFrontendKindSupportsMeasurementAndCorrectCollectionSemantics() {
    var u=user();
    for(String kind:List.of("washer","dryer","dishwasher","oven","hob","custom")) {
      var d=expect(201,"POST","/appliances",Map.of("kind",kind,"name","Device "+kind),u.token(),key());
      assertTrue(d.get("notificationsEnabled").asBoolean());
      assertEquals(kind.equals("custom")?0:3,d.get("programs").size());
      var session=expect(201,"POST","/appliances/"+d.get("id").asText()+"/sessions",Map.of("timingMode","stopwatch"),u.token(),key());
      assertEquals("timer.session",session.get("programSnapshot").get("localizationKey").asText());assertTrue(session.get("expectedEnd").isNull());
      clock.advance(63);var done=action(u,session,"complete");assertEquals(63,done.get("elapsedSeconds").asLong());
      boolean collect=List.of("washer","dryer","dishwasher").contains(kind);assertEquals(collect,done.get("collectedAt").isNull());
      var measured=expect(200,"POST","/sessions/"+session.get("id").asText()+"/measured-program",Map.of("name","Measured 家"),u.token(),key());
      assertEquals(2,measured.get("measuredProgram").get("minutes").asInt());assertEquals(0,measured.get("programSnapshot").get("minutes").asInt());
      assertEquals(done.get("completedAt"),measured.get("completedAt"));assertEquals(done.get("collectedAt"),measured.get("collectedAt"));
      if(collect)action(u,session,"collect");
    }
    expect(400,"POST","/appliances",Map.of("kind","custom"),u.token(),key());
    expect(400,"POST","/appliances",Map.of("kind","custom","name","  "),u.token(),key());
  }

  @Test
  void measuredSaveIsImmutableAndDoesNotResurrectDeletedProgram() {
    var u=user();var d=device(u,"oven");String appliance=d.get("id").asText();
    var s=expect(201,"POST","/appliances/"+appliance+"/sessions",Map.of("mode","stopwatch","program",Map.of("name","Bake","minutes",0)),u.token(),key());
    String path="/sessions/"+s.get("id").asText()+"/measured-program";
    expect(409,"POST",path,Map.of("name","Measured"),u.token(),key());clock.advance(61);action(u,s,"complete");
    String command=key();var saved=expect(200,"POST",path,Map.of("name","Measured"),u.token(),command);
    assertEquals(saved,expect(200,"POST",path,Map.of("name","Measured"),u.token(),command));
    var device=expect(200,"GET","/appliances/"+appliance,null,u.token(),null);
    var program=device.get("programs").valueStream().filter(p -> p.get("name").asText().equals("Measured")).findFirst().orElseThrow();
    expect(200,"DELETE","/appliances/"+appliance+"/programs/"+program.get("id").asText(),null,u.token(),key());
    expect(200,"POST",path,Map.of("name","Measured"),u.token(),key());
    assertEquals(3,expect(200,"GET","/appliances/"+appliance,null,u.token(),null).get("programs").size());
    expect(409,"POST",path,Map.of("name","Other"),u.token(),key());
    var repeated=expect(201,"POST","/sessions/"+s.get("id").asText()+"/repeat",null,u.token(),key());
    assertNotEquals(s.get("id"),repeated.get("id"));assertEquals("countdown",repeated.get("timingMode").asText());assertEquals(2,repeated.get("minutes").asInt());
    var other=user();expect(404,"POST","/sessions/"+s.get("id").asText()+"/repeat",null,other.token(),key());
  }

  @Test
  void countdownOverrideAndDeletedPresetKeepTranslatedHistoricalSnapshot() {
    var u=user();var d=device(u,"washer");String appliance=d.get("id").asText(),original=d.get("programs").get(0).get("id").asText();
    var s=expect(201,"POST","/appliances/"+appliance+"/sessions",Map.of("programId",original,"minutes",17),u.token(),key());
    assertEquals(17,s.get("programSnapshot").get("minutes").asInt());assertEquals("Bumbac 40°C",s.get("programSnapshot").get("localizationKey").asText());
    assertEquals(original,s.get("sourceProgramId").asText());
    expect(200,"DELETE","/appliances/"+appliance+"/programs/"+s.get("programId").asText(),null,u.token(),key());
    var historical=expect(200,"GET","/sessions/"+s.get("id").asText(),null,u.token(),null);
    assertTrue(historical.get("programId").isNull());assertEquals(s.get("programSnapshot"),historical.get("programSnapshot"));
    var events=expect(200,"GET","/activity",null,u.token(),null).get("items");
    assertEquals("Cotton 40°C",events.get(0).get("message").get("arguments").get(0).asText());
    expect(200,"PATCH","/me",Map.of("language","ja"),u.token(),key());
    assertEquals("綿40°C",expect(200,"GET","/activity",null,u.token(),null).get("items").get(0).get("message").get("arguments").get(0).asText());
  }

  @Test
  void visibleCharacterLimitsAndOnboardingMirrorFrontend() {
    var u=user();String emoji="🧑‍🔧";
    expect(201,"POST","/appliances",Map.of("kind","custom","name",emoji.repeat(40)),u.token(),key());
    expect(400,"POST","/appliances",Map.of("kind","custom","name",emoji.repeat(41)),u.token(),key());
    var profile=expect(200,"PATCH","/me",Map.of("displayName","","onboarded",true),u.token(),key());
    assertEquals("",profile.get("displayName").asText());assertTrue(profile.get("onboarded").asBoolean());
    expect(400,"PATCH","/me",Map.of("displayName",emoji.repeat(41)),u.token(),key());
    for(String language:List.of("en","ro","es","it","fr","de","pl","hi","ja"))assertEquals(200,raw("GET","/api/v1/localizations/"+language,null,null).status());
    assertEquals(400,raw("GET","/api/v1/localizations/xx",null,null).status());
  }

  @Test
  void ownedSharedStatusReadsCurrentSessionAndRevokeIsOwnerOnly() {
    var u=user();var d=device(u,"washer");var s=start(u,d,"Shared",2);
    String command=key();var share=expect(201,"POST","/sessions/"+s.get("id").asText()+"/share",null,u.token(),command);
    assertEquals(share,expect(201,"POST","/sessions/"+s.get("id").asText()+"/share",null,u.token(),command));
    assertNotEquals(s.get("id"),share.get("snapshot").get("id"));assertEquals(300,share.get("refreshAfterSeconds").asInt());
    assertFalse(share.toString().contains(u.id()));assertFalse(share.toString().contains("writerHash"));assertFalse(share.toString().contains(d.get("id").asText()));
    String path=publicPath(share);clock.advance(120);assertEquals("due",raw("GET",path,null,null).body().get("status").asText());
    action(u,s,"complete");assertEquals("finished",raw("GET",path,null,null).body().get("status").asText());
    action(u,s,"collect");assertEquals("collected",raw("GET",path,null,null).body().get("status").asText());
    var other=user();expect(404,"POST","/sessions/"+s.get("id").asText()+"/share",null,other.token(),key());
    String token=path.substring(path.lastIndexOf('/')+1);
    expect(404,"DELETE","/session-shares/"+token,null,other.token(),key());
    assertEquals(403,raw("DELETE",path,null,org.adancau.doneapi.common.Crypto.randomToken()).status());
    expect(200,"DELETE","/session-shares/"+token,null,u.token(),key());assertEquals(410,raw("GET",path,null,null).status());
  }

  @Test
  void localProjectionProtocolKeepsReadAndWriteCapabilitiesSeparate() {
    var snapshot=localSnapshot();String writer=org.adancau.doneapi.common.Crypto.randomToken();UUID command=UUID.randomUUID();
    var input=Map.of("snapshot",snapshot,"revision",1,"writeKey",writer,"commandId",command);
    var created=raw("POST","/api/shares",input,null);assertEquals(201,created.status());assertFalse(created.body().toString().contains(writer));
    assertEquals(created.body().get("shareUrl"),raw("POST","/api/shares",input,null).body().get("shareUrl"));
    String path=publicPath(created.body());assertEquals(403,raw("PUT",path,Map.of("snapshot",snapshot,"revision",2),null).status());
    clock.advance(30);snapshot.put("capturedAt",clock.instant().toString());snapshot.put("completedAt",clock.instant().toString());
    assertEquals("finished",raw("PUT",path,Map.of("snapshot",snapshot,"revision",3),writer).body().get("status").asText());
    assertEquals(409,raw("PUT",path,Map.of("snapshot",snapshot,"revision",2),writer).status());
    assertEquals(204,raw("DELETE","/api/shares/by-command/"+command,null,writer).status());
    assertEquals(410,raw("GET",path,null,null).status());assertEquals(410,raw("POST","/api/shares",input,null).status());
  }

  @Test
  void deletingAccountAlsoDeletesPublicOwnedLinksAndInvalidatesTokens() {
    var u=user();var d=device(u,"oven");var s=start(u,d,"Share",3);
    var share=expect(201,"POST","/sessions/"+s.get("id").asText()+"/share",null,u.token(),key());
    expect(204,"DELETE","/me",Map.of("password",PASSWORD),u.token(),null);
    assertEquals(404,raw("GET",publicPath(share),null,null).status());expect(401,"GET","/me",null,u.token(),null);
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM session_shares",Integer.class));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM appliance_sessions",Integer.class));
  }

  @Test
  void localSharingValidatesUnknownFieldsStateTimesAndExpiry() {
    var snapshot=localSnapshot();String writer=org.adancau.doneapi.common.Crypto.randomToken();
    var input=new LinkedHashMap<String,Object>();input.put("snapshot",snapshot);input.put("revision",1);input.put("writeKey",writer);input.put("commandId",UUID.randomUUID());
    snapshot.put("ownerId",UUID.randomUUID());assertEquals(400,raw("POST","/api/shares",input,null).status());snapshot.remove("ownerId");
    snapshot.put("completedAt",clock.instant().plusSeconds(60).toString());assertEquals(400,raw("POST","/api/shares",input,null).status());snapshot.remove("completedAt");
    var share=raw("POST","/api/shares",input,null);assertEquals(201,share.status());clock.advance(604801);
    assertEquals(410,raw("GET",publicPath(share.body()),null,null).status());
  }

  @Test
  void appleDeletionLoginNeverCreatesAccountsAndUsesRecentAppleAuthentication() {
    var passwordUser=user();
    when(apple.authenticate(any(),anyString())).thenReturn(new AppleGateway.Identity("linked-sub","linked@example.com","native-refresh"));
    var challenge=expect(200,"POST","/auth/apple/challenge",null,null,null);
    expect(200,"POST","/me/identities/apple",Map.of("challengeId",challenge.get("challengeId").asText(),"identityToken","test","authorizationCode","test"),passwordUser.token(),null);
    when(apple.authenticate(any(),anyString())).thenReturn(new AppleGateway.Identity("linked-sub","linked@example.com","web-refresh","ro.done.web"));
    challenge=expect(200,"POST","/auth/apple/challenge",null,null,null);
    var login=expect(200,"POST","/auth/apple/delete-login",Map.of("challengeId",challenge.get("challengeId").asText(),"identityToken","test","authorizationCode","test","clientId","ro.done.web"),null,null);
    expect(204,"DELETE","/me",null,login.get("accessToken").asText(),null);verify(apple).revoke("web-refresh","ro.done.web");
    when(apple.authenticate(any(),anyString())).thenReturn(new AppleGateway.Identity("unknown-sub",null,"unknown-refresh"));
    challenge=expect(200,"POST","/auth/apple/challenge",null,null,null);
    expect(401,"POST","/auth/apple/delete-login",Map.of("challengeId",challenge.get("challengeId").asText(),"identityToken","test","authorizationCode","test"),null,null);
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app_users",Integer.class));
  }

  @Test
  void frontendStatisticsUseRollingWindowsMeasuredProgramAndSevenDayChart() {
    var u=user();var d=device(u,"oven");
    var s=expect(201,"POST","/appliances/"+d.get("id").asText()+"/sessions",Map.of("mode","stopwatch"),u.token(),key());clock.advance(63);action(u,s,"complete");
    expect(200,"POST","/sessions/"+s.get("id").asText()+"/measured-program",Map.of("name","Measured"),u.token(),key());
    for(var period:Map.of("week",7,"month",30,"year",365).entrySet()) {
      var stats=expect(200,"GET","/statistics/rolling?period="+period.getKey(),null,u.token(),null);
      assertEquals(period.getValue(),stats.get("windowDays").asInt());assertEquals(1,stats.get("completedSessions").asInt());
      assertEquals(63,stats.get("averageDurationSeconds").asLong());assertEquals(7,stats.get("usagePerDay").size());
      assertEquals("Measured",stats.get("mostUsedProgram").get("name").asText());assertEquals(1,stats.get("recentSessions").size());
      assertEquals(clock.instant().minusSeconds(period.getValue()*86400L).toString(),stats.get("from").asText());
    }
  }

  @Test
  void publicPagesAndConfigurationAreAccessibleAndNeverExposePrivateConfiguration() throws Exception {
    for(String path:List.of("/","/privacy","/privacy-policy","/support","/contact","/delete-account","/account-deletion")) {
      var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
      assertEquals(200,response.statusCode(),path);assertTrue(response.body().contains("/site/site.js"));
      assertEquals("no-referrer",response.headers().firstValue("Referrer-Policy").orElseThrow());
      assertTrue(response.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
    }
    var config=raw("GET","/api/v1/public-config",null,null);assertEquals(200,config.status());assertTrue(config.body().get("mock").asBoolean());
    assertFalse(config.body().toString().contains("secret"));assertFalse(config.body().toString().contains("encryption"));assertFalse(config.body().get("appleWebEnabled").asBoolean());
  }


  String requestRecovery(User user) throws Exception {
    expect(202,"POST","/auth/forgot-password",Map.of("email",user.email()),null,null);
    assertTrue(recovery.deliverNext());
    String body=smtp.takeBody();
    var match=java.util.regex.Pattern.compile("#token=([A-Za-z0-9_-]{43})").matcher(body);
    assertTrue(match.find(),"Reset capability missing from SMTP message");
    assertTrue(body.contains("http://127.0.0.1:8080/reset-password?lang="));
    return match.group(1);
  }

  @Test
  void passwordResetDeliversRealEmailConsumesTokenAndRevokesEverySession() throws Exception {
    var u=user();var second=expect(200,"POST","/auth/login",Map.of("email",u.email(),"password",PASSWORD),null,null);
    String token=requestRecovery(u),newPassword="New secure password 123!";
    String stored=jdbc.queryForObject("SELECT token_hash FROM password_reset_tokens",String.class);
    assertNotEquals(token,stored);assertEquals(org.adancau.doneapi.common.Crypto.hash(token),stored);
    expect(204,"POST","/auth/reset-password",Map.of("token",token,"newPassword",newPassword),null,null);
    expect(401,"GET","/me",null,u.token(),null);
    expect(401,"GET","/me",null,second.get("accessToken").asText(),null);
    expect(401,"POST","/auth/refresh",Map.of("refreshToken",u.refresh()),null,null);
    expect(401,"POST","/auth/login",Map.of("email",u.email(),"password",PASSWORD),null,null);
    expect(200,"POST","/auth/login",Map.of("email",u.email(),"password",newPassword),null,null);
    assertEquals("invalid_reset_token",expect(400,"POST","/auth/reset-password",Map.of("token",token,"newPassword",PASSWORD),null,null).get("code").asText());
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens",Integer.class));
    assertTrue(recovery.deliverNext());String changed=smtp.takeBody();assertFalse(changed.contains(newPassword));assertFalse(changed.contains("#token="));
  }

  @Test
  void recoveryUnknownAppleOnlyAndThrottledAddressesHaveIdenticalResponses() throws Exception {
    var u=user();var accepted=expect(202,"POST","/auth/forgot-password",Map.of("email",u.email()),null,null);
    assertEquals(accepted,expect(202,"POST","/auth/forgot-password",Map.of("email","nobody@example.test"),null,null));
    for(int i=0;i<12;i++)assertEquals(accepted,expect(202,"POST","/auth/forgot-password",Map.of("email",u.email()),null,null));
    assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM recovery_mail_queue",Integer.class));
    // An Apple-only identity must not gain a password through email recovery.
    jdbc.update("UPDATE app_users SET password_hash=NULL WHERE id=?",UUID.fromString(u.id()));
    while(recovery.deliverNext()){}
    assertTrue(smtp.messages.isEmpty());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens",Integer.class));
  }

  @Test
  void resetLinksExpireAndAuthenticatedPasswordChangeCancelsTokensAndQueuedMail() throws Exception {
    var u=user();String token=requestRecovery(u);clock.advance(900);
    expect(400,"POST","/auth/reset-password",Map.of("token",token,"newPassword",PASSWORD),null,null);
    // Authenticate again after the old access token expires.
    var logged=expect(200,"POST","/auth/login",Map.of("email",u.email(),"password",PASSWORD),null,null);
    token=requestRecovery(u);
    expect(202,"POST","/auth/forgot-password",Map.of("email",u.email()),null,null);
    expect(204,"POST","/me/password",Map.of("currentPassword",PASSWORD,"newPassword","Another password 123!"),logged.get("accessToken").asText(),null);
    expect(400,"POST","/auth/reset-password",Map.of("token",token,"newPassword",PASSWORD),null,null);
    assertFalse(recovery.deliverNext());assertTrue(smtp.messages.isEmpty());
  }

  @Test
  void resetTokenCannotWinTwiceUnderConcurrentRequests() throws Exception {
    var u=user();String token=requestRecovery(u);
    try(var pool=Executors.newFixedThreadPool(2)) {
      var gate=new CountDownLatch(1);var futures=new ArrayList<Future<Response>>();
      for(int i=0;i<2;i++)futures.add(pool.submit(() -> {gate.await();return call("POST","/auth/reset-password",Map.of("token",token,"newPassword","Concurrent password 123!"),null,null);}));
      gate.countDown();var statuses=new ArrayList<Integer>();for(var f:futures)statuses.add(f.get(15,TimeUnit.SECONDS).status());
      statuses.sort(Integer::compareTo);assertEquals(List.of(204,400),statuses);
    }
  }

  @Test
  void recoveryRejectsInvalidAndOversizedPasswordsWithoutConsumingLink() throws Exception {
    var u=user();String token=requestRecovery(u);
    for(String password:List.of("short","é".repeat(40),"a".repeat(65)))
      expect(400,"POST","/auth/reset-password",Map.of("token",token,"newPassword",password),null,null);
    expect(400,"POST","/auth/reset-password",Map.of("token",org.adancau.doneapi.common.Crypto.randomToken(),"newPassword",PASSWORD),null,null);
    expect(204,"POST","/auth/reset-password",Map.of("token",token,"newPassword",PASSWORD),null,null);
  }

  @Test
  void failedSmtpDeliveryRemainsQueuedAndRetriesWithoutExposingToken() throws Exception {
    var u=user();smtp.rejectNext=true;
    var body=expect(202,"POST","/auth/forgot-password",Map.of("email",u.email()),null,null);
    assertFalse(body.toString().contains("token"));assertTrue(recovery.deliverNext());assertTrue(smtp.messages.isEmpty());
    assertEquals(1,jdbc.queryForObject("SELECT attempts FROM recovery_mail_queue",Integer.class));
    assertFalse(recovery.deliverNext());clock.advance(60);assertTrue(recovery.deliverNext());assertTrue(smtp.takeBody().contains("#token="));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM recovery_mail_queue",Integer.class));
  }

  @Test
  void deleteAccountCancelsOutstandingRecovery() throws Exception {
    var u=user();String token=requestRecovery(u);
    expect(202,"POST","/auth/forgot-password",Map.of("email",u.email()),null,null);
    jdbc.update("INSERT INTO recovery_mail_queue(id,email,kind,created_at,expires_at,next_attempt_at) VALUES (?,?,'changed',?,?,?)",
        UUID.randomUUID(),u.email(),java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant().plusSeconds(3600)),java.sql.Timestamp.from(clock.instant()));
    expect(204,"DELETE","/me",Map.of("password",PASSWORD),u.token(),null);
    expect(400,"POST","/auth/reset-password",Map.of("token",token,"newPassword",PASSWORD),null,null);
    assertFalse(recovery.deliverNext());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens",Integer.class));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM recovery_mail_queue",Integer.class));
  }

  @Test
  void everyPrivateControllerRouteRequiresAuthenticationBeforeDtoParsing() {
    for(var info:mapping.getHandlerMethods().keySet())for(String template:info.getPatternValues()) {
      if(!template.startsWith("/api/v1/") || template.startsWith("/api/v1/localizations/") || template.equals("/api/v1/public-config"))continue;
      if(template.startsWith("/api/v1/auth/") && !template.contains("logout"))continue;
      String path=template.replaceAll("\\{[^}]+}",UUID.randomUUID().toString());
      for(var method:info.getMethodsCondition().getMethods())
        assertEquals(401,raw(method.name(),path,null,null).status(),method+" "+path);
    }
    var u=user();
    for(String path:List.of("/api/v1/future-admin","/api/v1/auth/login","/api/shares"))
      assertEquals(path.startsWith("/api/shares")?401:403,raw("PATCH",path,Map.of(),u.token()).status());
    assertEquals(403,raw("GET","/actuator/env",null,u.token()).status());
    assertEquals(403,raw("GET","/site/private-config.json",null,u.token()).status());
  }

  @Test
  void injectionPayloadsAreLiteralDataAndNeverChangeOwnershipOrSchema() {
    var u=user();var other=user();String payload="x'); DROP TABLE app_users;--";
    var appliance=expect(201,"POST","/appliances",Map.of("kind","custom","name",payload),u.token(),key());
    assertEquals(payload,appliance.get("name").asText());assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM app_users",Integer.class));
    expect(404,"GET","/appliances/"+appliance.get("id").asText(),null,other.token(),null);
    expect(400,"GET","/appliances/'%20OR%201=1--",null,u.token(),null);
    assertNotEquals(200,call("POST","/auth/login",Map.of("email","' OR 1=1 --@example.test","password",PASSWORD),null,null).status());
    var script=expect(201,"POST","/households",Map.of("name","<script>alert(1)</script>"),u.token(),key());
    assertEquals("<script>alert(1)</script>",script.get("name").asText());
    assertEquals(1,expect(200,"GET","/households",null,other.token(),null).size());
  }

  @Test
  void malformedJsonAndUnexpectedFieldsAreRejected() throws Exception {
    String url="http://127.0.0.1:"+port+"/api/v1/auth/login";
    for(String body:List.of("{\"email\":\"a@example.test\",\"email\":\"b@example.test\",\"password\":\"x\"}",
        "{\"email\":\"a@example.test\",\"password\":\"x\"} {}", "{\"extra\":"+"[".repeat(40)+"0"+"]".repeat(40)+"}")) {
      var response=http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
      assertEquals(400,response.statusCode());assertFalse(response.body().contains("Exception"));
    }
    expect(400,"POST","/auth/register",Map.of("email","a@example.test","password",PASSWORD,"displayName","A","roles",List.of("ADMIN")),null,null);
  }

  @Test
  void recoveryPagesUseStrictHeadersAndDoNotAllowForeignOrigins() throws Exception {
    for(String page:List.of("/forgot-password","/reset-password")) {
      var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+page)).GET().build(),HttpResponse.BodyHandlers.ofString());
      assertEquals(200,response.statusCode());assertEquals("no-referrer",response.headers().firstValue("Referrer-Policy").orElseThrow());
      assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
      assertEquals("DENY",response.headers().firstValue("X-Frame-Options").orElseThrow());
      assertFalse(response.headers().firstValue("Content-Security-Policy").orElseThrow().contains("appleid"));
      assertTrue(response.body().contains("/site/recovery.js"));assertFalse(response.body().contains("token="));
    }
    var cors=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me"))
        .header("Origin","https://attacker.invalid").header("Access-Control-Request-Method","PATCH").method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    assertTrue(cors.statusCode()>=400);assertTrue(cors.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
  }


  @Test
  void jwtRejectsTamperingWrongAudienceIssuerAndMalformedSessionClaims() throws Exception {
    var u=user();String sid=com.nimbusds.jwt.SignedJWT.parse(u.token()).getJWTClaimsSet().getStringClaim("sid");
    var valid=new HashMap<String,Object>();valid.put("sub",u.id());valid.put("sid",sid);valid.put("iss","done-api");
    valid.put("aud",List.of("done-apple"));valid.put("iat",clock.instant());valid.put("exp",clock.instant().plusSeconds(900));valid.put("nbf",clock.instant());
    for(var invalid:List.<Map<String,Object>>of(Map.of("aud",List.of("other-app")),Map.of("iss","other-issuer"),Map.of("sid","not-a-uuid"),
        Map.of("iat",clock.instant().plusSeconds(60)),Map.of("exp",clock.instant().plusSeconds(86400)),Map.of("nbf",clock.instant().plusSeconds(60)))) {
      var claims=new HashMap<>(valid);claims.putAll(invalid);
      var encoded=jwtEncoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
          org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),
          org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().claims(c -> c.putAll(claims)).build()));
      expect(401,"GET","/me",null,encoded.getTokenValue(),null);
    }
    String[] parts=u.token().split("\\.");
    expect(401,"GET","/me",null,parts[0]+"."+parts[1]+"."+org.adancau.doneapi.common.Crypto.randomToken(),null);
  }
}
