package org.adancau.doneapi.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "household_invitations")
public class HouseholdInvitationEntity {
  @Id private UUID id = UUID.randomUUID();
  private UUID householdId;

  @Column(length = 64)
  private String tokenHash;

  private Instant createdAt;
  private Instant expiresAt;
  private Instant redeemedAt;
  private Instant revokedAt;
  private UUID redeemedBy;
}
