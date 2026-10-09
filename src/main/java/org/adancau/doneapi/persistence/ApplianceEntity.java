package org.adancau.doneapi.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.adancau.doneapi.appliance.ApplianceKind;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "appliances")
public class ApplianceEntity {
  @Id private UUID id = UUID.randomUUID();
  private UUID userId;
  private UUID householdId;

  @Column(length = 60)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(length = 20)
  private ApplianceKind kind;

  private boolean notificationsEnabled = true;

  private Instant createdAt;
  @Version private long version;
}
