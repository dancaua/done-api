package org.adancau.doneapi.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "app_users")
public class UserEntity {
  @Id private UUID id = UUID.randomUUID();

  @Column(length = 254)
  private String email;

  @Column(length = 100)
  private String passwordHash;

  @Column(nullable = false, length = 80)
  private String displayName;

  @Column(nullable = false, length = 64)
  private String timezone;

  @Column(nullable = false, length = 2)
  private String language = org.adancau.doneapi.common.SupportedLanguages.DEFAULT;

  private boolean notificationsEnabled;
  private boolean onboarded;

  private long revision;

  private Instant createdAt;
}
