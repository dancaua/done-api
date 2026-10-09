package org.adancau.doneapi.site;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("app.site")
public record SiteProperties(boolean mock, String origin, String operatorName, String operatorAddress,
    String supportEmail, String privacyEmail, String hostingRegion, String processors, int backupRetentionDays) {}
