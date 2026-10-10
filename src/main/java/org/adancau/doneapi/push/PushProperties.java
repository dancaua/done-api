package org.adancau.doneapi.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.push")
public record PushProperties(
    boolean enabled,
    String teamId,
    String keyId,
    String privateKeyPath,
    String privateKeyBase64,
    String topic) {}
