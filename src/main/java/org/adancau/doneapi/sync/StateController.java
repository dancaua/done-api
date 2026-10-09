package org.adancau.doneapi.sync;

import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class StateController {
  private final StateService service;

  public StateController(StateService service) {
    this.service = service;
  }

  @GetMapping("/state")
  public StateDtos.Snapshot snapshot(@AuthenticationPrincipal Jwt jwt) {
    return service.snapshot(UUID.fromString(jwt.getSubject()));
  }

  @GetMapping("/me/export")
  public ResponseEntity<StateDtos.Export> export(@AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=done-export.json")
        .body(service.export(UUID.fromString(jwt.getSubject())));
  }
}
