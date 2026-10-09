package org.adancau.doneapi.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "apple_identities")
public class AppleIdentityEntity {
  @Id private UUID id = UUID.randomUUID();
  private UUID userId;
  private String subject;

  @Column(columnDefinition = "text")
  private String encryptedRefreshToken;

  @Column(length=255) private String clientId;
  private Instant createdAt;
}
