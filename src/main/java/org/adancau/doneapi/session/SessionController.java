package org.adancau.doneapi.session;

import jakarta.validation.Valid;
import java.util.UUID;
import org.adancau.doneapi.common.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class SessionController {
  private final SessionService service;

  public SessionController(SessionService service) {
    this.service = service;
  }

  @PostMapping("/appliances/{id}/sessions")
  @ResponseStatus(HttpStatus.CREATED)
  public SessionDtos.SessionView start(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody SessionDtos.StartSession r) {
    return service.start(UUID.fromString(jwt.getSubject()), id, key, r);
  }

  @GetMapping("/appliances/{id}/sessions")
  public PageResponse<SessionDtos.SessionView> history(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.history(UUID.fromString(jwt.getSubject()), id, PageResponse.page(page, size));
  }

  @PostMapping("/sessions/{id}/measured-program")
  public SessionDtos.SessionView measured(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody SessionDtos.MeasuredProgram r) {
    return service.measured(UUID.fromString(jwt.getSubject()), id, key, r);
  }

  @PostMapping("/sessions/{id}/repeat")
  @ResponseStatus(HttpStatus.CREATED)
  public SessionDtos.SessionView repeat(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key) {
    return service.repeat(UUID.fromString(jwt.getSubject()), id, key);
  }

  @GetMapping("/sessions/{id}")
  public SessionDtos.SessionView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.get(UUID.fromString(jwt.getSubject()), id);
  }

  @PostMapping("/sessions/{id}/extend")
  public SessionDtos.SessionView extend(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody SessionDtos.ExtendSession r) {
    return service.action(UUID.fromString(jwt.getSubject()), id, key, "extend", r);
  }

  @PostMapping("/sessions/{id}/complete")
  public SessionDtos.SessionView complete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key) {
    return service.action(UUID.fromString(jwt.getSubject()), id, key, "complete", null);
  }

  @PostMapping("/sessions/{id}/collect")
  public SessionDtos.SessionView collect(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key) {
    return service.action(UUID.fromString(jwt.getSubject()), id, key, "collect", null);
  }

  @PostMapping("/sessions/{id}/cancel")
  public SessionDtos.SessionView cancel(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key) {
    return service.action(UUID.fromString(jwt.getSubject()), id, key, "cancel", null);
  }
}
