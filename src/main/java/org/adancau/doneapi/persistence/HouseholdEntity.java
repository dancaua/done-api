package org.adancau.doneapi.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "households")
public class HouseholdEntity {
  @Id private UUID id = UUID.randomUUID();
  private UUID userId;

  @Column(length = 60)
  private String name;

  @Column(length = 60)
  private String localizationKey;

  private Instant createdAt;
  @Version private long version;
}
