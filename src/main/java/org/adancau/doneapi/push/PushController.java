package org.adancau.doneapi.push;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/devices")
public class PushController {
  private final PushService push;

  public PushController(PushService p) {
    push = p;
  }

  public record Registration(
      @NotBlank @Size(max = 256) String token, @NotBlank String environment) {}

  @PutMapping("/{id}")
  public Map<String, Object> register(
      @AuthenticationPrincipal Jwt j, @PathVariable UUID id, @Valid @RequestBody Registration r) {
    return push.register(
        UUID.fromString(j.getSubject()),
        UUID.fromString(j.getClaimAsString("sid")),
        id,
        r.token(),
        r.environment());
  }

  @DeleteMapping("/{id}")
  public void delete(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
    push.unregister(UUID.fromString(j.getSubject()), id);
  }
}
