package org.adancau.doneapi.session;

import java.time.Instant;
import java.util.*;
import org.adancau.doneapi.persistence.*;
import org.springframework.stereotype.Service;

@Service
public class SessionEvents {
  private final ActivityRepository activity;
  private final tools.jackson.databind.json.JsonMapper json;

  public SessionEvents(ActivityRepository activity, tools.jackson.databind.json.JsonMapper json) {
    this.activity = activity; this.json=json;
  }

  public void event(
      SessionEntity s, ApplianceEntity a, String kind, String title, String detail, Instant at) {
    var e = new ActivityEntity();
    e.setUserId(s.getUserId());
    e.setApplianceId(a.getId());
    e.setSessionId(s.getId());
    e.setEventKey(s.getId() + ":" + kind + ":" + at);
    e.setKind(kind);
    e.setTitle(title);
    e.setDetail(detail);
    String titleKey=switch(kind) {
      case "started" -> "Timer pornit.";
      case "finished" -> switch(a.getKind()) {
        case washer -> "Rufele sunt gata!"; case dryer -> "Rufele sunt uscate!";
        case dishwasher -> "Vasele sunt curate!"; default -> "Sesiune încheiată!"; };
      case "halfway" -> title.equals("Mai sunt 5 minute.") ? "La jumătate — încă 5 minute." : "Suntem la jumătate!";
      case "five_minutes" -> "Încă 5 minute."; case "due" -> "E timpul să verifici.";
      case "reminder30" -> "Psst…"; case "reminder120" -> "Încă te așteaptă…";
      default -> "Oprită";
    };
    String messageKey=switch(kind) {
      case "started" -> s.getMode()==TimerMode.stopwatch ? "{0} · Cronometru" : "{0} · {1} min";
      case "finished" -> "{0} · Finalizare confirmată de tine.";
      case "halfway" -> "{0}: jumătate din durata programului a trecut.";
      case "five_minutes" -> "{0}: programul ar trebui să fie gata în curând.";
      case "due" -> "{0}: durata estimată a trecut.";
      case "reminder30" -> "{0} te așteaptă de 30 de minute.";
      case "reminder120" -> "{0}: au trecut două ore de la finalizare.";
      default -> "{0}";
    };
    e.setTitleKey(titleKey); e.setMessageKey(messageKey);
    java.util.List<String> arguments=kind.equals("started") ?
        (s.getMode()==TimerMode.stopwatch ? List.of(s.getProgramName()) : List.of(s.getProgramName(),String.valueOf(s.getMinutes()))) : List.of(a.getName());
    e.setMessageArguments(json.writeValueAsString(arguments));
    if (kind.equals("started")) e.setProgramSnapshot(json.writeValueAsString(
        new org.adancau.doneapi.appliance.ApplianceDtos.ProgramSnapshot(s.getProgramName(),s.getMinutes(),s.getProgramLocalizationKey(),s.isProgramCustom())));
    e.setOccurredAt(at);
    activity.save(e);
  }

  public void backdateStarted(SessionEntity s) {
    // Keep the event identity/read status; only its actual start timestamp is corrected.
    activity.findBySessionIdAndUserIdAndKind(s.getId(), s.getUserId(), "started")
        .ifPresent(event -> event.setOccurredAt(s.getStartedAt()));
  }

  public boolean process(SessionEntity s, ApplianceEntity a, Instant now) {
    boolean changed = false;
    if (s.isOpen() && s.getCompletedAt() != null) {
      var done = s.getCompletedAt();
      if (!s.isReminder30Sent() && !now.isBefore(done.plusSeconds(1800))) {
        s.setReminder30Sent(true);
        changed = true;
        event(
            s,
            a,
            "reminder30",
            "Psst…",
            a.getName() + " te așteaptă de 30 de minute.",
            done.plusSeconds(1800));
      }
      if (!s.isReminder120Sent() && !now.isBefore(done.plusSeconds(7200))) {
        s.setReminder120Sent(true);
        changed = true;
        event(
            s,
            a,
            "reminder120",
            "Încă te așteaptă…",
            a.getName() + ": au trecut două ore de la finalizare.",
            done.plusSeconds(7200));
      }
    } else if (s.isOpen() && s.getExpectedEnd() != null) {
      var half = s.getStartedAt().plusSeconds(s.getMinutes() * 30L);
      var five = s.getExpectedEnd().minusSeconds(300);
      boolean combine = s.getMinutes() > 5 && half.equals(five);
      if (!s.isHalfwaySent() && !now.isBefore(half)) {
        s.setHalfwaySent(true);
        changed = true;
        if (combine) s.setFiveSent(true);
        event(
            s,
            a,
            "halfway",
            combine ? "Mai sunt 5 minute." : "Suntem la jumătate.",
            a.getName()
                + ": "
                + (combine
                    ? "programul a ajuns la jumătate; mai sunt 5 minute."
                    : "programul a ajuns la jumătate."),
            half);
      }
      if (s.getMinutes() > 5 && !s.isFiveSent() && !now.isBefore(five)) {
        s.setFiveSent(true);
        changed = true;
        event(
            s,
            a,
            "five_minutes",
            "Mai sunt 5 minute.",
            a.getName() + " este aproape de final.",
            five);
      }
      if (!s.isDueSent() && !now.isBefore(s.getExpectedEnd())) {
        s.setDueSent(true);
        changed = true;
        event(
            s,
            a,
            "due",
            "E timpul să verifici.",
            a.getName() + ": durata estimată a trecut.",
            s.getExpectedEnd());
      }
    }
    reschedule(s);
    return changed;
  }

  public void reschedule(SessionEntity s) {
    var times = new ArrayList<Instant>();
    if (s.isOpen() && s.getCompletedAt() != null) {
      if (!s.isReminder30Sent()) times.add(s.getCompletedAt().plusSeconds(1800));
      if (!s.isReminder120Sent()) times.add(s.getCompletedAt().plusSeconds(7200));
    } else if (s.isOpen() && s.getExpectedEnd() != null) {
      if (!s.isHalfwaySent()) times.add(s.getStartedAt().plusSeconds(s.getMinutes() * 30L));
      if (s.getMinutes() > 5 && !s.isFiveSent()) times.add(s.getExpectedEnd().minusSeconds(300));
      if (!s.isDueSent()) times.add(s.getExpectedEnd());
    }
    s.setNextEventAt(times.stream().min(Comparator.naturalOrder()).orElse(null));
  }
}
