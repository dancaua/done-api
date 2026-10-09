package org.adancau.doneapi.appliance;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.adancau.doneapi.session.SessionDtos.SessionView;

public final class ApplianceDtos {
  private ApplianceDtos() {}

  public record CreateAppliance(
      @NotNull ApplianceKind kind,
      String name,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          UUID householdId,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          Boolean notificationsEnabled) {}

  public record MoveAppliance(@NotNull UUID householdId, @NotNull @PositiveOrZero Long version) {}

  public record ApplianceNotificationSettings(
      @NotNull Boolean notificationsEnabled, @NotNull @PositiveOrZero Long version) {}

  public record RenameAppliance(
      @NotBlank String name, @NotNull @PositiveOrZero Long version) {}

  public record ProgramInput(
      String name, @NotNull @Min(0) @Max(1440) Integer minutes) {}

  public record UpdateProgram(
      @NotBlank String name,
      @NotNull @Min(1) @Max(1440) Integer minutes,
      @NotNull @PositiveOrZero Long version) {}

  public record ProgramSnapshot(String name, int minutes, String localizationKey, boolean isCustom) {}
  public record ProgramView(UUID id, String name, int minutes, boolean suggested, long version,
      UUID applianceId, String localizationKey, boolean isCustom, int sortOrder) {}

  public record ApplianceView(
      UUID id,
      UUID householdId,
      String name,
      ApplianceKind kind,
      String status,
      Boolean notificationsEnabled,
      List<ProgramView> programs,
      SessionView activeSession,
      Instant createdAt,
      long version) {
    // Receipts written before V6 contain no per-appliance setting. Their default was on.
    public ApplianceView {
      if (notificationsEnabled == null) notificationsEnabled = true;
    }
  }

  public record CatalogItem(
      ApplianceKind kind,
      String title,
      String defaultName,
      boolean supportsStopwatch,
      boolean needsCollection,
      List<ApplianceKind.Suggestion> programs, String titleKey) {}
}
