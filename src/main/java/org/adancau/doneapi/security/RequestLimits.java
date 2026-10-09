package org.adancau.doneapi.security;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Fixed-window admission gate. Keys have fixed categories; capacity exhaustion fails closed. */
@Component
public class RequestLimits {
  private record Bucket(long until, int requests) {}
  private final Map<String, Bucket> buckets = new HashMap<>();
  private final Clock clock;
  private long nextCleanup;
  public RequestLimits(Clock clock) { this.clock = clock; }

  public synchronized boolean consume(String key, int limit, int seconds, int capacity) {
    long now = clock.instant().getEpochSecond();
    if (now >= nextCleanup) {
      buckets.values().removeIf(b -> b.until <= now);
      nextCleanup = now + 30;
    }
    var b = buckets.get(key);
    if (b != null && b.until > now) {
      if (b.requests >= limit) return false;
      buckets.put(key, new Bucket(b.until, b.requests + 1));
      return true;
    }
    if (b == null && buckets.size() >= capacity) return false;
    buckets.put(key, new Bucket(now + seconds, 1));
    return true;
  }
}
