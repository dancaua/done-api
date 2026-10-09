package org.adancau.doneapi.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.recovery")
public record RecoveryProperties(boolean enabled, boolean deliveryEnabled, String from,
    Duration tokenTtl, int queueCapacity) {}
