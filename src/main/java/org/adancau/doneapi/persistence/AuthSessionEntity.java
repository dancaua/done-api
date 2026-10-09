package org.adancau.doneapi.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "auth_sessions")
public class AuthSessionEntity {
  @Id private UUID id = UUID.randomUUID();
  private UUID userId;
  @Column(length=20) private String authenticationMethod = "password";
  private Instant createdAt;
  private Instant expiresAt;
  private Instant revokedAt;
}
