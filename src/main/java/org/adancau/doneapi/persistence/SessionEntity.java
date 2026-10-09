package org.adancau.doneapi.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.adancau.doneapi.session.TimerMode;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "appliance_sessions")
public class SessionEntity {
  @Id private UUID id = UUID.randomUUID();

  private UUID userId;

  private UUID applianceId;

  private UUID programId;
  private UUID sourceProgramId;
  @Column(length = 200) private String programLocalizationKey;
  private boolean programCustom = true;
  @Column(columnDefinition = "text") private String measuredProgramName;
  private Integer measuredProgramMinutes;

  @Column(length = 60)
  private String programName;

  private int minutes;

  @Enumerated(EnumType.STRING)
  @Column(length = 20)
  private TimerMode mode;

  private Instant startedAt;

  private Instant expectedEnd;
  private Instant completedAt;
  private Instant collectedAt;
  private Instant canceledAt;
  private boolean halfwaySent;
  private boolean fiveSent;
  private boolean dueSent;
  private boolean reminder30Sent;
  private boolean reminder120Sent;
  private Instant nextEventAt;
  @Version private long version;

  public boolean isOpen() {
    return collectedAt == null && canceledAt == null;
  }
}
