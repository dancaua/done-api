package org.adancau.doneapi.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "mutation_receipts")
public class MutationReceiptEntity {
  @Id private UUID id = UUID.randomUUID();
  private UUID userId;
  private UUID requestId;

  @Column(length = 64)
  private String requestHash;

  @Column(columnDefinition = "text")
  private String responseJson;

  private Instant createdAt;
}
