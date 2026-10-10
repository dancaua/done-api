package org.adancau.doneapi.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "household_members")
public class HouseholdMemberEntity {
  @Id private UUID id = UUID.randomUUID();
  private UUID householdId;
  private UUID userId;
  private boolean notificationsEnabled = true;
  private Instant joinedAt;
}
