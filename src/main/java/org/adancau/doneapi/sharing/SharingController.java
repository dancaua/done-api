package org.adancau.doneapi.sharing;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.adancau.doneapi.sharing.SharingDtos.*;
@RestController
public class SharingController {
  private final SharingService service;
  public SharingController(SharingService service) { this.service=service; }
  @GetMapping("/api/shares/{token}")
  public LiveActivityShareDTO get(@PathVariable String token) { return service.get(token); }
  @PostMapping("/api/shares") @ResponseStatus(HttpStatus.CREATED)
  public LiveActivityShareDTO create(@Valid @RequestBody CreateProjection r) { return service.createProjection(r); }
  @PutMapping("/api/shares/{token}")
  public LiveActivityShareDTO update(@PathVariable String token,@RequestHeader(value="Authorization",required=false) String key,
      @Valid @RequestBody UpdateProjection r) { return service.updateProjection(token,key,r); }
  @DeleteMapping("/api/shares/{token}") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable String token,@RequestHeader(value="Authorization",required=false) String key) { service.revokeProjection(token,key); }
  @DeleteMapping("/api/shares/by-command/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeCommand(@PathVariable UUID id,@RequestHeader(value="Authorization",required=false) String key) { service.revokeCommand(id,key); }
  @PostMapping("/api/v1/sessions/{id}/share") @ResponseStatus(HttpStatus.CREATED)
  public LiveActivityShareDTO owned(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key) { return service.createOwned(UUID.fromString(jwt.getSubject()),id,key); }
  @GetMapping("/api/v1/session-shares")
  public List<LiveActivityShareDTO> list(@AuthenticationPrincipal Jwt jwt) { return service.list(UUID.fromString(jwt.getSubject())); }
  @DeleteMapping("/api/v1/session-shares/{token}")
  public org.adancau.doneapi.account.AccountDtos.MutationResult stop(@AuthenticationPrincipal Jwt jwt,@PathVariable String token,@RequestHeader("Idempotency-Key") UUID key) { return service.revokeOwned(UUID.fromString(jwt.getSubject()),token,key); }
}
