package org.adancau.doneapi.household;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.*;

public final class CollaborationDtos {
  public record Member(
      UUID userId,
      String displayName,
      String role,
      Instant joinedAt,
      boolean notificationsEnabled) {}

  public record Invitation(
      UUID id,
      UUID householdId,
      String householdName,
      String localizationKey,
      String ownerName,
      String inviteUrl,
      Instant expiresAt) {}

  public record InviteSummary(
      UUID id, Instant createdAt, Instant expiresAt, boolean used, boolean revoked) {}

  public record Settings(@NotNull Boolean notificationsEnabled) {}

  public record Joined(UUID householdId) {}
}
