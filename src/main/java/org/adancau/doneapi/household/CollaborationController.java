package org.adancau.doneapi.household;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CollaborationController {
  private final CollaborationService service;

  public CollaborationController(CollaborationService s) {
    service = s;
  }

  private UUID actor(Jwt j) {
    return UUID.fromString(j.getSubject());
  }

  @GetMapping("/households/{id}/members")
  public List<CollaborationDtos.Member> members(
      @AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
    return service.members(actor(j), id);
  }

  @GetMapping("/households/{id}/invitations")
  public List<CollaborationDtos.InviteSummary> invites(
      @AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
    return service.invitations(actor(j), id);
  }

  @PostMapping("/households/{id}/invitations")
  public CollaborationDtos.Invitation create(
      @AuthenticationPrincipal Jwt j,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key) {
    return service.create(actor(j), id, key);
  }

  @GetMapping("/household-invitations/{token}")
  public CollaborationDtos.Invitation preview(
      @AuthenticationPrincipal Jwt j, @PathVariable String token) {
    return service.preview(actor(j), token);
  }

  @PostMapping("/household-invitations/{token}/accept")
  public CollaborationDtos.Joined accept(
      @AuthenticationPrincipal Jwt j,
      @PathVariable String token,
      @RequestHeader("Idempotency-Key") UUID key) {
    return service.accept(actor(j), token, key);
  }

  @DeleteMapping("/households/{id}/invitations/{invite}")
  public org.adancau.doneapi.account.AccountDtos.MutationResult revoke(
      @AuthenticationPrincipal Jwt j,
      @PathVariable UUID id,
      @PathVariable UUID invite,
      @RequestHeader("Idempotency-Key") UUID key) {
    return service.revoke(actor(j), id, invite, key);
  }

  @DeleteMapping("/households/{id}/members/{member}")
  public org.adancau.doneapi.account.AccountDtos.MutationResult remove(
      @AuthenticationPrincipal Jwt j,
      @PathVariable UUID id,
      @PathVariable UUID member,
      @RequestHeader("Idempotency-Key") UUID key) {
    return service.remove(actor(j), id, member, key);
  }

  @PatchMapping("/households/{id}/membership")
  public org.adancau.doneapi.account.AccountDtos.MutationResult settings(
      @AuthenticationPrincipal Jwt j,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID key,
      @Valid @RequestBody CollaborationDtos.Settings r) {
    return service.settings(actor(j), id, key, r);
  }
}
