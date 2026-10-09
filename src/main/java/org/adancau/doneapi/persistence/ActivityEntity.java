package org.adancau.doneapi.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "activity_events")
public class ActivityEntity {
  @Id private UUID id = UUID.randomUUID();
  private UUID userId;
  private UUID applianceId;
  private UUID sessionId;

  @Column(length = 200)
  private String eventKey;

  @Column(length = 30)
  private String kind;

  @Column(length = 150)
  private String title;

  @Column(length = 300)
  private String detail;
  @Column(length = 200) private String titleKey;
  @Column(length = 200) private String messageKey;
  @Column(columnDefinition = "text") private String messageArguments;
  @Column(columnDefinition = "text") private String programSnapshot;

  private Instant occurredAt;
  private Instant readAt;
}
