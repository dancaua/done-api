package org.adancau.doneapi.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenEntity {
  @Id private UUID id = UUID.randomUUID();
  private UUID sessionId;

  @Column(length = 64)
  private String tokenHash;

  private Instant expiresAt;
  private Instant usedAt;
}
