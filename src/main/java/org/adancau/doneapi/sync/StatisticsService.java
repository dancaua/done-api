package org.adancau.doneapi.sync;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.adancau.doneapi.common.ApiException;
import org.adancau.doneapi.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatisticsService {
  public enum Period {
    week,
    month,
    year
  }

  public record FrontendStatistics(Period period, int windowDays, Instant from, Instant to,
      String timezone, long completedSessions, long totalDurationSeconds, long averageDurationSeconds,
      org.adancau.doneapi.appliance.ApplianceDtos.ProgramSnapshot mostUsedProgram,
      Double changePercent, List<Day> usagePerDay, List<org.adancau.doneapi.session.SessionDtos.SessionView> recentSessions) {}

  public record Day(LocalDate date, long sessions) {}

  public record Statistics(
      Period period,
      LocalDate from,
      LocalDate to,
      String timezone,
      long completedSessions,
      long totalDurationSeconds,
      long averageDurationSeconds,
      String mostUsedProgram,
      Double changePercent,
      List<Day> usagePerDay) {}

  private final UserRepository users;
  private final SessionRepository sessions;
  private final Clock clock;
  private final org.adancau.doneapi.session.DomainViews views;
  private final org.adancau.doneapi.common.MessageCatalog catalog;
  private final ApplianceRepository appliances;
  private final org.adancau.doneapi.household.HouseholdService households;

  public StatisticsService(
      UserRepository users,
      SessionRepository sessions,
      Clock clock,
      ApplianceRepository appliances,
      org.adancau.doneapi.household.HouseholdService households, org.adancau.doneapi.session.DomainViews views, org.adancau.doneapi.common.MessageCatalog catalog) {
    this.users = users;
    this.sessions = sessions;
    this.clock = clock;
    this.appliances = appliances;
    this.households = households; this.views=views; this.catalog=catalog;
  }

  @Transactional(readOnly = true)
  public Statistics get(UUID owner, Period period, LocalDate anchor, UUID householdId) {
    Set<UUID> applianceIds = null;
    if (householdId != null) {
      households.owned(owner, householdId);
      applianceIds =
          new HashSet<>(
              appliances
                  .findByUserIdAndHouseholdIdOrderByCreatedAtAscIdAsc(owner, householdId)
                  .stream()
                  .map(ApplianceEntity::getId)
                  .toList());
    }
    final Set<UUID> scoped = applianceIds;
    var user = users.findById(owner).orElseThrow(ApiException::unauthorized);
    var zone = ZoneId.of(user.getTimezone());
    var date = anchor == null ? LocalDate.now(clock.withZone(zone)) : anchor;
    if (date.getYear() < 1900 || date.getYear() > 2100)
      throw ApiException.invalid("Alege o dată între anii 1900 și 2100.");
    var from =
        switch (period) {
          case week -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
          case month -> date.withDayOfMonth(1);
          case year -> date.withDayOfYear(1);
        };
    var to =
        switch (period) {
          case week -> from.plusWeeks(1);
          case month -> from.plusMonths(1);
          case year -> from.plusYears(1);
        };
    var before =
        switch (period) {
          case week -> from.minusWeeks(1);
          case month -> from.minusMonths(1);
          case year -> from.minusYears(1);
        };
    var data =
        sessions
            .findByUserIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanAndCanceledAtIsNull(
                owner, from.atStartOfDay(zone).toInstant(), to.atStartOfDay(zone).toInstant())
            .stream()
            .filter(s -> scoped == null || scoped.contains(s.getApplianceId()))
            .toList();
    var previous =
        sessions
            .findByUserIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanAndCanceledAtIsNull(
                owner, before.atStartOfDay(zone).toInstant(), from.atStartOfDay(zone).toInstant())
            .stream()
            .filter(s -> scoped == null || scoped.contains(s.getApplianceId()))
            .toList();
    long total =
        data.stream()
            .mapToLong(s -> Duration.between(s.getStartedAt(), s.getCompletedAt()).toSeconds())
            .sum();
    var programs = new TreeMap<String, Long>();
    var counts = new HashMap<LocalDate, Long>();
    for (var s : data) {
      programs.merge(s.getProgramName(), 1L, Long::sum);
      counts.merge(s.getCompletedAt().atZone(zone).toLocalDate(), 1L, Long::sum);
    }
    String most =
        programs.entrySet().stream()
            .sorted(
                Map.Entry.<String, Long>comparingByValue()
                    .reversed()
                    .thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    var days = from.datesUntil(to).map(d -> new Day(d, counts.getOrDefault(d, 0L))).toList();
    Double change =
        previous.isEmpty() ? null : (data.size() - previous.size()) * 100.0 / previous.size();
    return new Statistics(
        period,
        from,
        to,
        zone.getId(),
        data.size(),
        total,
        data.isEmpty() ? 0 : total / data.size(),
        most,
        change,
        days);
  }
  @Transactional(readOnly=true)
  public FrontendStatistics frontend(UUID owner, Period period, UUID householdId) {
    var user=users.findById(owner).orElseThrow(ApiException::unauthorized);
    var zone=ZoneId.of(user.getTimezone());
    Set<UUID> scope=null;
    if (householdId!=null) {
      households.owned(owner,householdId);
      scope=new HashSet<>(appliances.findByUserIdAndHouseholdIdOrderByCreatedAtAscIdAsc(owner,householdId).stream().map(ApplianceEntity::getId).toList());
    }
    final Set<UUID> selected=scope;
    int days=switch(period) { case week -> 7; case month -> 30; case year -> 365; };
    Instant to=clock.instant(), from=to.minusSeconds(days*86400L);
    var data=sessions.findByUserIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanEqualAndCanceledAtIsNull(owner,from,to)
        .stream().filter(s -> selected==null || selected.contains(s.getApplianceId()))
        .sorted(Comparator.comparing(SessionEntity::getStartedAt).reversed().thenComparing(SessionEntity::getId)).toList();
    var previous=sessions.findByUserIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanAndCanceledAtIsNull(owner,from.minusSeconds(days*86400L),from)
        .stream().filter(s -> selected==null || selected.contains(s.getApplianceId())).toList();
    long total=data.stream().mapToLong(s -> Duration.between(s.getStartedAt(),s.getCompletedAt()).toSeconds()).sum();
    var names=new TreeMap<String,Long>();
    var values=new HashMap<String,org.adancau.doneapi.appliance.ApplianceDtos.ProgramSnapshot>();
    for (var s:data) {
      var p=views.measuredSnapshot(s);if(p==null)p=views.programSnapshot(s);
      String display=p.localizationKey()==null?p.name():catalog.text(user.getLanguage(),p.localizationKey(),List.of());
      names.merge(display,1L,Long::sum);values.putIfAbsent(display,p);
    }
    String most=names.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).map(Map.Entry::getKey).findFirst().orElse(null);
    var today=LocalDate.now(clock.withZone(zone));
    // The frontend always shows seven calendar days, regardless of metric period.
    var chart=sessions.findByUserIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanEqualAndCanceledAtIsNull(owner,today.minusDays(6).atStartOfDay(zone).toInstant(),to)
        .stream().filter(s -> selected==null || selected.contains(s.getApplianceId())).toList();
    var counts=new HashMap<LocalDate,Long>();chart.forEach(s -> counts.merge(s.getCompletedAt().atZone(zone).toLocalDate(),1L,Long::sum));
    var usage=today.minusDays(6).datesUntil(today.plusDays(1)).map(d -> new Day(d,counts.getOrDefault(d,0L))).toList();
    return new FrontendStatistics(period,days,from,to,zone.getId(),data.size(),total,data.isEmpty()?0:total/data.size(),
        most==null?null:values.get(most),previous.isEmpty()?null:(data.size()-previous.size())*100.0/previous.size(),usage,data.stream().limit(8).map(views::session).toList());
  }

}
