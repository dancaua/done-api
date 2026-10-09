package org.adancau.doneapi.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "apple_challenges")
public class AppleChallengeEntity {
  @Id private UUID id = UUID.randomUUID();

  @Column(length = 64)
  private String nonceHash;

  private Instant expiresAt;
  private Instant usedAt;
}
