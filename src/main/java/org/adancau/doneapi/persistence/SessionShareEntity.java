package org.adancau.doneapi.persistence;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
@Getter @Setter @NoArgsConstructor @Entity @Table(name="session_shares")
public class SessionShareEntity {
  @Id @Column(length=43) private String token;
  private UUID projectionId;
  private UUID ownerId;
  private UUID sessionId;
  private UUID commandId;
  @Column(length=64) private String writerHash;
  @Column(columnDefinition="text") private String snapshotJson;
  private long revision;
  private Instant capturedAt;
  private Instant createdAt;
  private Instant publishedAt;
  private Instant expiresAt;
  private Instant revokedAt;
  @Version private long version;
}
