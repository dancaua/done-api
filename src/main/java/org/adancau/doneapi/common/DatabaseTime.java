package org.adancau.doneapi.common;

import java.time.*;

/** JDBC TIMESTAMP columns use UTC, matching Hibernate, independently of the JVM's default zone. */
public final class DatabaseTime {
  private DatabaseTime() {}
  public static LocalDateTime at(Instant instant) { return LocalDateTime.ofInstant(instant,ZoneOffset.UTC); }
}
