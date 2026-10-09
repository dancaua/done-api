package org.adancau.doneapi.site;
import java.net.URI;
import java.util.*;
import org.adancau.doneapi.config.AppProperties;
import org.adancau.doneapi.sharing.SharingProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class SiteConfiguration {
  public SiteConfiguration(SiteProperties site, SharingProperties sharing, AppProperties props, Environment environment) {
    validate(site,sharing,props,Arrays.asList(environment.getActiveProfiles()).contains("prod"));
  }
  public static void validate(SiteProperties site, SharingProperties sharing, AppProperties props, boolean production) {
    URI origin;
    try { origin=URI.create(site.origin()); } catch(Exception e) { throw new IllegalStateException("PUBLIC_ORIGIN must be an absolute origin."); }
    boolean loopback=Set.of("127.0.0.1","localhost","[::1]").contains(origin.getHost()==null ? "" : origin.getHost());
    if (!("https".equals(origin.getScheme()) || site.mock() && loopback && "http".equals(origin.getScheme())) ||
        origin.getHost()==null || origin.getUserInfo()!=null || origin.getQuery()!=null || origin.getFragment()!=null ||
        origin.getRawPath()!=null && !origin.getRawPath().isEmpty() && !origin.getRawPath().equals("/"))
      throw new IllegalStateException("PUBLIC_ORIGIN must be HTTPS without path, credentials, query or fragment (mock loopback HTTP permitted).");
    if (sharing.ttl()==null || sharing.ttl().isNegative() || sharing.ttl().isZero() || sharing.ttl().compareTo(java.time.Duration.ofDays(7))>0 || sharing.maximumAnonymousShares()<1)
      throw new IllegalStateException("Invalid sharing retention/capacity configuration.");
    if (production) {
      if (site.mock() || loopback || placeholder(origin.getHost()))
        throw new IllegalStateException("Production cannot publish the mock public site.");
      for (String value:List.of(site.operatorName(),site.operatorAddress(),site.supportEmail(),site.privacyEmail(),site.hostingRegion(),site.processors()))
        if (value.isBlank() || value.toLowerCase(Locale.ROOT).contains("demo") || value.toLowerCase(Locale.ROOT).contains("replace") || placeholder(value.toLowerCase(Locale.ROOT)))
          throw new IllegalStateException("Configure real operator, contacts, hosting region and processors before production.");
      if (site.backupRetentionDays()<1 || site.backupRetentionDays()>90) throw new IllegalStateException("Configure backup retention of 1–90 days.");
    }
    for (String value:List.of(site.supportEmail(),site.privacyEmail()))
      if (!value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalStateException("Invalid public email configuration.");
    String web=props.apple().webClientId();
    if (web!=null && !web.isBlank()) {
      URI redirect=URI.create(props.apple().webRedirectUri());
      if (!props.apple().enabled() || !"https".equals(redirect.getScheme()) || !Objects.equals(redirect.getHost(),origin.getHost()) ||
          redirect.getPort()!=origin.getPort() || !redirect.getPath().equals("/delete-account") || redirect.getUserInfo()!=null || redirect.getQuery()!=null || redirect.getFragment()!=null)
        throw new IllegalStateException("Apple web login requires the registered PUBLIC_ORIGIN/delete-account HTTPS redirect and enabled Apple credentials.");
    }
  }
  private static boolean placeholder(String value) {
    return value.contains(".example") || value.contains(".invalid") || value.contains(".test") || value.contains(".localhost") ||
        value.contains("example.com") || value.contains("example.org") || value.contains("example.net") || value.contains("REPLACE");
  }

}
