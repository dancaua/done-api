package org.adancau.doneapi.activity;

import java.time.Instant;
import java.util.UUID;

public final class ActivityDtos {
  private ActivityDtos() {}

  public record LocalizedMessage(String key, java.util.List<String> arguments) {}

  public record ActivityView(
      UUID id,
      UUID applianceId,
      UUID sessionId,
      String kind,
      String title,
      String detail,
      Instant occurredAt,
      Instant readAt, String titleKey, LocalizedMessage message,
      org.adancau.doneapi.appliance.ApplianceDtos.ProgramSnapshot programSnapshot) {}
}
