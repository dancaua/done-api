package org.adancau.doneapi.persistence;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "programs")
public class ProgramEntity {
  @Id private UUID id = UUID.randomUUID();
  private UUID applianceId;
  private UUID userId;

  @Column(length = 60)
  private String name;

  private int minutes;
  @Column(length = 200) private String localizationKey;
  private boolean isCustom = true;
  private boolean suggested;
  private int position;
  @Version private long version;
}
