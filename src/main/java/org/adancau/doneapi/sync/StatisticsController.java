package org.adancau.doneapi.sync;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/statistics")
public class StatisticsController {
  private final StatisticsService service;

  public StatisticsController(StatisticsService service) {
    this.service = service;
  }

  @GetMapping("/rolling")
  public StatisticsService.FrontendStatistics rolling(@AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue="week") StatisticsService.Period period,
      @RequestParam(required=false) UUID householdId) {
    return service.frontend(UUID.fromString(jwt.getSubject()),period,householdId);
  }

  @GetMapping
  public StatisticsService.Statistics get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "week") StatisticsService.Period period,
      @RequestParam(required = false) LocalDate date,
      @RequestParam(required = false) UUID householdId) {
    return service.get(UUID.fromString(jwt.getSubject()), period, date, householdId);
  }
}
