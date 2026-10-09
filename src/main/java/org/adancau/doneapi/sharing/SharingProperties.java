package org.adancau.doneapi.sharing;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("app.sharing")
public record SharingProperties(boolean allowAnonymous, Duration ttl, int maximumAnonymousShares) {}
