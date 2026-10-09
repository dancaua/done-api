package org.adancau.doneapi.activity;

import java.util.UUID;
import org.adancau.doneapi.account.AccountDtos.MutationResult;
import org.adancau.doneapi.common.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/activity")
public class ActivityController {
  private final ActivityService service;

  public ActivityController(ActivityService service) {
    this.service = service;
  }

  @GetMapping
  public PageResponse<ActivityDtos.ActivityView> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) UUID householdId) {
    return service.list(
        UUID.fromString(jwt.getSubject()), PageResponse.page(page, size), householdId);
  }

  @PostMapping("/{id}/read")
  public ActivityDtos.ActivityView read(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key) {
    return service.read(UUID.fromString(jwt.getSubject()), id, key);
  }

  @PostMapping("/read-all")
  public MutationResult readAll(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestParam(required = false) UUID householdId) {
    return service.readAll(UUID.fromString(jwt.getSubject()), key, householdId);
  }
}
