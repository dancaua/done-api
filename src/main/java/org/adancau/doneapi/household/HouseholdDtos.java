package org.adancau.doneapi.household;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class HouseholdDtos {
  private HouseholdDtos() {}

  public record CreateHousehold(@NotBlank String name) {}

  public record RenameHousehold(
      @NotBlank String name, @NotNull @PositiveOrZero Long version) {}

  public record HouseholdView(
      UUID id,
      String name,
      String localizationKey,
      long applianceCount,
      Instant createdAt,
      long version, UUID ownerId) {}
}
