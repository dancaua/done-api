package org.adancau.doneapi.household;

import jakarta.validation.Valid;
import java.util.*;
import org.adancau.doneapi.account.AccountDtos.MutationResult;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/households")
public class HouseholdController {
  private final HouseholdService service;

  public HouseholdController(HouseholdService service) {
    this.service = service;
  }

  @GetMapping
  public List<HouseholdDtos.HouseholdView> list(@AuthenticationPrincipal Jwt jwt) {
    return service.list(UUID.fromString(jwt.getSubject()));
  }

  @GetMapping("/{id}")
  public HouseholdDtos.HouseholdView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.get(UUID.fromString(jwt.getSubject()), id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public HouseholdDtos.HouseholdView create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody HouseholdDtos.CreateHousehold r) {
    return service.create(UUID.fromString(jwt.getSubject()), key, r);
  }

  @PatchMapping("/{id}")
  public HouseholdDtos.HouseholdView rename(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody HouseholdDtos.RenameHousehold r) {
    return service.rename(UUID.fromString(jwt.getSubject()), id, key, r);
  }

  @DeleteMapping("/{id}")
  public MutationResult delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key) {
    return service.delete(UUID.fromString(jwt.getSubject()), id, key);
  }
}
