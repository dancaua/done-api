package org.adancau.doneapi.push;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.adancau.doneapi.common.*;
import org.adancau.doneapi.household.HouseholdAccess;
import org.adancau.doneapi.persistence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PushService {
  private final JdbcTemplate db;
  private final HouseholdAccess access;
  private final SessionRepository sessions;
  private final ApplianceRepository appliances;
  private final UserRepository users;
  private final Clock clock;
  private final PushGateway gateway;
  private final MessageCatalog catalog;

  public PushService(
      JdbcTemplate d,
      HouseholdAccess a,
      SessionRepository s,
      ApplianceRepository p,
      UserRepository u,
      Clock c,
      PushGateway g,
      MessageCatalog m) {
    db = d;
    access = a;
    sessions = s;
    appliances = p;
    users = u;
    clock = c;
    gateway = g;
    catalog = m;
  }

  public boolean enabled() {
    return gateway.enabled();
  }

  public static String generation(SessionEntity s) {
    return Crypto.hash(
        s.getStartedAt()
            + ":"
            + s.getExpectedEnd()
            + ":"
            + s.getCompletedAt()
            + ":"
            + s.getCanceledAt()
            + ":"
            + s.getCollectedAt());
  }

  private record Device(String id, String user, String token, String environment) {}

  private List<Device> devices(UUID home) {
    var result = new ArrayList<Device>();
    for (var user : access.participants(home)) {
      if (!access.notifications(user, home)
          || !users.findById(user).map(UserEntity::isNotificationsEnabled).orElse(false)) continue;
      result.addAll(
          db.query(
              "select id,user_id,token,environment from push_devices where user_id=? AND"
                  + " auth_session_id IN (SELECT id FROM auth_sessions WHERE revoked_at IS NULL AND"
                  + " expires_at>? )",
              (r, n) -> new Device(r.getString(1), r.getString(2), r.getString(3), r.getString(4)),
              user.toString(),
              Timestamp.from(clock.instant())));
    }
    return result;
  }

  private void queue(SessionEntity s, ApplianceEntity a, String kind, Instant at) {
    if (!gateway.enabled()
        || !a.isNotificationsEnabled()
        || access.participants(a.getHouseholdId()).size() < 2) return;
    for (var d : devices(a.getHouseholdId()))
      db.update(
          "INSERT IGNORE INTO"
              + " push_deliveries(id,device_id,session_id,kind,generation,due_at,next_attempt_at)"
              + " VALUES (?,?,?,?,?,?,?)",
          UUID.randomUUID().toString(),
          d.id(),
          s.getId().toString(),
          kind,
          generation(s),
          Timestamp.from(at),
          Timestamp.from(at));
  }

  public void event(SessionEntity s, ApplianceEntity a, String kind) {
    if (Set.of("started", "finished", "canceled", "collected").contains(kind))
      queue(s, a, kind, clock.instant());
  }

  public void reschedule(SessionEntity s, ApplianceEntity a) {
    db.update(
        "DELETE FROM push_deliveries WHERE session_id=? AND sent_at IS NULL AND due_at>?",
        s.getId().toString(),
        Timestamp.from(clock.instant()));
    if (!s.isOpen()) return;
    var times = new LinkedHashMap<String, Instant>();
    if (s.getCompletedAt() != null) {
      times.put("reminder30", s.getCompletedAt().plusSeconds(1800));
      times.put("reminder120", s.getCompletedAt().plusSeconds(7200));
    } else if (s.getExpectedEnd() != null) {
      var half = s.getStartedAt().plusSeconds(s.getMinutes() * 30L);
      var five = s.getExpectedEnd().minusSeconds(300);
      times.put("halfway", half);
      if (s.getMinutes() > 5 && !half.equals(five)) times.put("five_minutes", five);
      times.put("due", s.getExpectedEnd());
      for (int min : new int[] {10, 30, 60, 120, 300})
        times.put("overdue_" + min, s.getExpectedEnd().plusSeconds(min * 60L));
    }
    times.forEach(
        (kind, at) -> {
          if (at.isAfter(clock.instant())) queue(s, a, kind, at);
        });
  }

  @Transactional
  public void refreshHome(UUID home) {
    for (var s :
        sessions.visible(List.of(home), org.springframework.data.domain.Pageable.unpaged()))
      if (s.isOpen())
        reschedule(s, appliances.findById(s.getApplianceId()).orElseThrow(ApiException::missing));
  }

  @Transactional
  public Map<String, Object> register(
      UUID actor, UUID authSession, UUID id, String token, String environment) {
    users.lockById(actor).orElseThrow(ApiException::unauthorized);
    if (db.queryForObject(
            "SELECT COUNT(*) FROM auth_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL AND"
                + " expires_at>?",
            Long.class,
            authSession.toString(),
            actor.toString(),
            Timestamp.from(clock.instant()))
        != 1) throw ApiException.unauthorized();
    if (!token.matches("(?:[a-fA-F0-9]{2}){16,128}")
        || !Set.of("sandbox", "production").contains(environment))
      throw ApiException.invalid("Invalid device registration.");
    token = token.toLowerCase(Locale.ROOT);
    var owners =
        db.queryForList("select user_id from push_devices where id=?", String.class, id.toString());
    if (!owners.isEmpty() && !owners.get(0).equals(actor.toString())) throw ApiException.missing();
    if (owners.isEmpty()
        && db.queryForObject(
                "select count(*) from push_devices where user_id=? AND auth_session_id IN (SELECT"
                    + " id FROM auth_sessions WHERE revoked_at IS NULL AND"
                    + " expires_at>UTC_TIMESTAMP(6))",
                Long.class,
                actor.toString())
            >= 10) throw ApiException.conflict("device_limit", "Maximum 10 registered devices.");
    db.update(
        "DELETE FROM push_devices WHERE environment=? AND token=? AND id<>?",
        environment,
        token,
        id.toString());
    db.update(
        "INSERT INTO push_devices(id,user_id,auth_session_id,token,environment,updated_at) VALUES"
            + " (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
            + " auth_session_id=VALUES(auth_session_id),token=VALUES(token),environment=VALUES(environment),updated_at=VALUES(updated_at)",
        id.toString(),
        actor.toString(),
        authSession.toString(),
        token,
        environment,
        Timestamp.from(clock.instant()));
    for (var home : access.homes(actor)) refreshHome(home);
    return Map.of("enabled", enabled());
  }

  @Transactional
  public void unregister(UUID actor, UUID id) {
    db.update("DELETE FROM push_devices WHERE id=? AND user_id=?", id.toString(), actor.toString());
  }

  public void deliver() {
    if (!gateway.enabled()) return;
    var now = clock.instant();
    var ids =
        db.queryForList(
            "SELECT id FROM push_deliveries WHERE sent_at IS NULL AND next_attempt_at<=? AND"
                + " (lease_until IS NULL OR lease_until<?) ORDER BY next_attempt_at,id LIMIT 20",
            String.class,
            Timestamp.from(now),
            Timestamp.from(now));
    for (var id : ids) {
      if (db.update(
              "UPDATE push_deliveries SET lease_until=? WHERE id=? AND sent_at IS NULL AND"
                  + " (lease_until IS NULL OR lease_until<?)",
              Timestamp.from(now.plusSeconds(90)),
              id,
              Timestamp.from(now))
          == 0) continue;
      try {
        deliverOne(id);
      } catch (Exception ignored) {
        db.update(
            "UPDATE push_deliveries SET next_attempt_at=?,lease_until=NULL,attempts=attempts+1"
                + " WHERE id=?",
            Timestamp.from(clock.instant().plusSeconds(60)),
            id);
      }
    }
    db.update(
        "DELETE FROM push_deliveries WHERE due_at<?",
        Timestamp.from(clock.instant().minus(Duration.ofDays(7))));
  }

  private void finish(String id) {
    db.update(
        "UPDATE push_deliveries SET sent_at=?,lease_until=NULL WHERE id=?",
        Timestamp.from(clock.instant()),
        id);
  }

  private void deliverOne(String id) {
    var jobs =
        db.queryForList(
            "SELECT q.*,d.user_id,d.token,d.environment,t.revoked_at,t.expires_at AS"
                + " auth_expires_at FROM push_deliveries q JOIN push_devices d ON d.id=q.device_id"
                + " JOIN auth_sessions t ON t.id=d.auth_session_id WHERE q.id=?",
            id);
    if (jobs.isEmpty()) return;
    var job = jobs.get(0);
    UUID actor = UUID.fromString((String) job.get("user_id"));
    var s = sessions.findById(UUID.fromString((String) job.get("session_id"))).orElse(null);
    if (s == null) {
      finish(id);
      return;
    }
    var a = appliances.findById(s.getApplianceId()).orElse(null);
    Instant due = ((Timestamp) job.get("due_at")).toInstant();
    if (job.get("revoked_at") != null
        || !((Timestamp) job.get("auth_expires_at")).toInstant().isAfter(clock.instant())
        || a == null
        || !generation(s).equals(job.get("generation"))
        || !a.isNotificationsEnabled()
        || !users.findById(actor).map(UserEntity::isNotificationsEnabled).orElse(false)
        || clock.instant().isAfter(due.plusSeconds(300))) {
      finish(id);
      return;
    }
    try {
      if (!access.notifications(actor, a.getHouseholdId())
          || access.participants(a.getHouseholdId()).size() < 2) {
        finish(id);
        return;
      }
    } catch (ApiException e) {
      finish(id);
      return;
    }
    String language = users.findById(actor).map(UserEntity::getLanguage).orElse("en"),
        kind = (String) job.get("kind");
    String title, body;
    String titleKey =
        switch (kind) {
          case "started" -> "Timer pornit.";
          case "finished" ->
              switch (a.getKind()) {
                case washer -> "Rufele sunt gata!";
                case dryer -> "Rufele sunt uscate!";
                case dishwasher -> "Vasele sunt curate!";
                default -> "Sesiune încheiată!";
              };
          case "collected" -> "home.push.collected";
          case "halfway" ->
              s.getExpectedEnd() != null
                      && s.getStartedAt()
                          .plusSeconds(s.getMinutes() * 30L)
                          .equals(s.getExpectedEnd().minusSeconds(300))
                  ? "La jumătate — încă 5 minute."
                  : "Suntem la jumătate!";
          case "five_minutes" -> "Încă 5 minute.";
          case "due" -> "E timpul să verifici.";
          case "reminder30" -> "Psst…";
          case "reminder120" -> "Încă te așteaptă…";
          case "canceled" -> "Oprită";
          default -> "overdue.notification.title";
        };
    title = catalog.text(language, titleKey, List.of());
    String bodyKey =
        switch (kind) {
          case "finished" -> "home.push.completedBody";
          case "collected" -> "home.push.collectedBody";
          case "halfway" -> "{0}: jumătate din durata programului a trecut.";
          case "five_minutes" -> "{0}: programul ar trebui să fie gata în curând.";
          case "due" -> "{0}: programul ar trebui să fie gata. Confirmă în DONE.";
          case "reminder30" -> "{0}: aparatul tău te așteaptă.";
          case "reminder120" -> "{0}: au trecut două ore de la finalizare.";
          default -> "{0}";
        };
    body = catalog.text(language, bodyKey, List.of(a.getName()));
    if (kind.startsWith("overdue_")) {
      int min = Integer.parseInt(kind.substring(8));
      String delay =
          catalog.text(
              language,
              min < 60 ? "overdue.minutes" : "overdue.hours",
              List.of(String.valueOf(min < 60 ? min : min / 60)));
      body = catalog.text(language, "overdue.notification.body", List.of(a.getName(), delay));
    }
    if (kind.equals("reminder120")
        || kind.startsWith("overdue_") && Integer.parseInt(kind.substring(8)) >= 120) {
      String care =
          switch (a.getKind()) {
            case washer -> "care.washer";
            case dryer -> "care.dryer";
            case dishwasher -> "care.dishwasher";
            default -> null;
          };
      if (care != null) body += " " + catalog.text(language, care, List.of());
    }
    var payload =
        Map.<String, Object>of(
            "aps",
            Map.of(
                "alert",
                Map.of("title", title, "body", body),
                "sound",
                "default",
                "thread-id",
                a.getId().toString(),
                "content-available",
                1),
            "applianceID",
            a.getId().toString(),
            "cycleID",
            s.getId().toString());
    var result =
        gateway.send(
            (String) job.get("environment"),
            (String) job.get("token"),
            payload,
            Crypto.hash(s.getId() + ":" + kind));
    if (result.invalidToken())
      db.update(
          "DELETE FROM push_devices WHERE id=? AND token=? AND environment=?",
          job.get("device_id"),
          job.get("token"),
          job.get("environment"));
    else if (result.delivered()
        || !result.retryable()
        || ((Number) job.get("attempts")).intValue() >= 7) finish(id);
    else
      db.update(
          "UPDATE push_deliveries SET next_attempt_at=?,lease_until=NULL,attempts=attempts+1 WHERE"
              + " id=?",
          Timestamp.from(
              clock
                  .instant()
                  .plusSeconds(
                      Math.min(
                          300, 15L << (Math.min(4, ((Number) job.get("attempts")).intValue()))))),
          id);
  }
}
