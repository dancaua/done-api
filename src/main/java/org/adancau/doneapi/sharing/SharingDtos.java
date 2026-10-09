package org.adancau.doneapi.sharing;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import org.adancau.doneapi.appliance.ApplianceKind;
import org.adancau.doneapi.appliance.ApplianceDtos.ProgramSnapshot;
import org.adancau.doneapi.session.TimerMode;
public final class SharingDtos {
  private SharingDtos() {}
  public record SharedSnapshot(@NotNull @Min(1) @Max(1) Integer schemaVersion, @NotNull UUID id,
      @NotNull String applianceName, @NotNull ApplianceKind kind, @NotNull ProgramSnapshot program,
      @NotNull TimerMode timingMode, @NotNull Instant startedAt, Instant expectedEnd, Instant completedAt,
      Instant collectedAt, Instant canceledAt, @NotNull Instant capturedAt,
      @NotNull @Pattern(regexp=org.adancau.doneapi.common.SupportedLanguages.PATTERN) String language) {}
  public record CreateProjection(@NotNull @Valid SharedSnapshot snapshot,
      @NotNull @PositiveOrZero Long revision, @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String writeKey,
      @NotNull UUID commandId) {}
  public record UpdateProjection(@NotNull @Valid SharedSnapshot snapshot, @NotNull @PositiveOrZero Long revision) {}
  public record LiveActivityShareDTO(String shareUrl, SharedSnapshot snapshot, String status, Instant publishedAt,
      Instant serverTime, Instant expiresAt, int refreshAfterSeconds) {}
}
