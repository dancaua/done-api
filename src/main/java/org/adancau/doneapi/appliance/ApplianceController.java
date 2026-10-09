package org.adancau.doneapi.appliance;

import jakarta.validation.Valid;
import java.util.*;
import org.adancau.doneapi.account.AccountDtos.MutationResult;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ApplianceController {
  private final ApplianceService service;
  private final org.adancau.doneapi.common.MessageCatalog catalog;
  private final org.adancau.doneapi.persistence.UserRepository users;

  public ApplianceController(ApplianceService service, org.adancau.doneapi.common.MessageCatalog catalog, org.adancau.doneapi.persistence.UserRepository users) {
    this.service = service; this.catalog=catalog; this.users=users;
  }

  @GetMapping("/catalog")
  public List<ApplianceDtos.CatalogItem> catalog(@AuthenticationPrincipal Jwt jwt) {
    String language=users.findById(UUID.fromString(jwt.getSubject())).orElseThrow(org.adancau.doneapi.common.ApiException::unauthorized).getLanguage();
    return Arrays.stream(ApplianceKind.values())
        .map(
            k ->
                new ApplianceDtos.CatalogItem(
                    k,
                    catalog.text(language,k.title,List.of()),
                    k.defaultName,
                    true,
                    k.needsCollection(),
                    k.suggestions(), k.title))
        .toList();
  }

  @GetMapping("/appliances")
  public List<ApplianceDtos.ApplianceView> list(
      @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID householdId) {
    return service.list(UUID.fromString(jwt.getSubject()), householdId);
  }

  @PostMapping("/appliances")
  @ResponseStatus(HttpStatus.CREATED)
  public ApplianceDtos.ApplianceView create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody ApplianceDtos.CreateAppliance r) {
    return service.create(UUID.fromString(jwt.getSubject()), key, r);
  }

  @GetMapping("/appliances/{id}")
  public ApplianceDtos.ApplianceView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.get(UUID.fromString(jwt.getSubject()), id);
  }

  @PatchMapping("/appliances/{id}")
  public ApplianceDtos.ApplianceView rename(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody ApplianceDtos.RenameAppliance r) {
    return service.rename(UUID.fromString(jwt.getSubject()), id, key, r);
  }

  @PatchMapping("/appliances/{id}/household")
  public ApplianceDtos.ApplianceView move(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody ApplianceDtos.MoveAppliance r) {
    return service.move(UUID.fromString(jwt.getSubject()), id, key, r);
  }

  @PatchMapping("/appliances/{id}/notifications")
  public ApplianceDtos.ApplianceView notifications(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody ApplianceDtos.ApplianceNotificationSettings r) {
    return service.notificationSettings(UUID.fromString(jwt.getSubject()), id, key, r);
  }

  @DeleteMapping("/appliances/{id}")
  public MutationResult delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key) {
    return service.delete(UUID.fromString(jwt.getSubject()), id, key);
  }

  @PostMapping("/appliances/{id}/programs")
  @ResponseStatus(HttpStatus.CREATED)
  public ApplianceDtos.ProgramView program(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody ApplianceDtos.ProgramInput r) {
    return service.createProgram(UUID.fromString(jwt.getSubject()), id, key, r);
  }

  @PatchMapping("/appliances/{id}/programs/{programId}")
  public ApplianceDtos.ProgramView updateProgram(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @PathVariable UUID programId,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody ApplianceDtos.UpdateProgram r) {
    return service.updateProgram(UUID.fromString(jwt.getSubject()), id, programId, key, r);
  }

  @DeleteMapping("/appliances/{id}/programs/{programId}")
  public MutationResult deleteProgram(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @PathVariable UUID programId,
      @RequestHeader("Idempotency-Key") UUID key) {
    return service.deleteProgram(UUID.fromString(jwt.getSubject()), id, programId, key);
  }
}
