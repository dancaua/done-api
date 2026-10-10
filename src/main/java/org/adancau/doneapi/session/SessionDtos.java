package org.adancau.doneapi.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAlias;
import org.adancau.doneapi.appliance.ApplianceDtos.ProgramInput;
import org.adancau.doneapi.appliance.ApplianceDtos.ProgramSnapshot;

public final class SessionDtos {
  private SessionDtos() {}
  public record StartSession(UUID programId, @Valid ProgramInput program,
      @JsonAlias("timingMode") TimerMode mode, Boolean saveProgram,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) @Min(1) @Max(1440) Integer minutes,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) @Min(0) @Max(1440) Integer elapsedMinutes) {}
  public record ExtendSession(@Min(1) @Max(1440) int minutes) {}
  public record BackdateSession(@Min(1) @Max(1440) int minutes) {}
  public record MeasuredProgram(@NotBlank String name) {}
  public record SessionView(UUID id, UUID applianceId, UUID programId,
      String programName, int minutes, TimerMode mode, String status,
      Instant startedAt, Instant expectedEnd, Instant completedAt, Instant collectedAt,
      Instant canceledAt, long elapsedSeconds, long version, Instant serverTime,
      UUID sourceProgramId, ProgramSnapshot programSnapshot, ProgramSnapshot measuredProgram,
      TimerMode timingMode, boolean isOpen) {}
}
