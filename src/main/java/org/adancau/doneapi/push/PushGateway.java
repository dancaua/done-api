package org.adancau.doneapi.push;

import java.util.Map;

public interface PushGateway {
  boolean enabled();

  record Result(boolean delivered, boolean invalidToken, boolean retryable) {}

  Result send(String environment, String token, Map<String, Object> payload, String collapseId);
}
