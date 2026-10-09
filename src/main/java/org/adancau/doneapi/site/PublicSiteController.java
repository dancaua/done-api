package org.adancau.doneapi.site;
import java.util.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.adancau.doneapi.config.AppProperties;

@RestController
public class PublicSiteController {
  private final SiteProperties site;
  private final AppProperties props;
  public PublicSiteController(SiteProperties site,AppProperties props) { this.site=site;this.props=props; }
  @GetMapping(value={"/","/privacy","/privacy-policy","/support","/contact","/delete-account","/account-deletion"},produces=MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<Resource> page() {
    return ResponseEntity.ok().header("Referrer-Policy","no-referrer")
        .header("Cross-Origin-Opener-Policy","same-origin-allow-popups")
        .body(new ClassPathResource("static/site/index.html"));
  }
  @GetMapping(value={"/forgot-password","/reset-password"},produces=MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<Resource> recovery() {
    return ResponseEntity.ok().header("Cache-Control","no-store").header("Referrer-Policy","no-referrer")
        .header("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        .header("Cross-Origin-Opener-Policy","same-origin")
        .body(new ClassPathResource("static/site/recovery.html"));
  }
  @GetMapping(value="/share/{token}",produces=MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<Resource> share(@PathVariable String token) {
    if (!token.matches("[A-Za-z0-9_-]{43}")) return ResponseEntity.notFound().build();
    return ResponseEntity.ok().header("Referrer-Policy","no-referrer").body(new ClassPathResource("static/share/index.html"));
  }
  @GetMapping("/api/v1/public-config")
  public Map<String,Object> configuration() {
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("mock",site.mock());result.put("operatorName",site.operatorName());result.put("operatorAddress",site.operatorAddress());
    result.put("supportEmail",site.supportEmail());result.put("privacyEmail",site.privacyEmail());result.put("hostingRegion",site.hostingRegion());
    result.put("processors",site.processors());result.put("backupRetentionDays",site.backupRetentionDays());result.put("effectiveDate","2026-10-08");
    boolean web=props.apple().enabled() && props.apple().webClientId()!=null && !props.apple().webClientId().isBlank();
    result.put("appleWebEnabled",web);result.put("appleWebClientId",web ? props.apple().webClientId() : "");
    result.put("appleWebRedirectURI",web ? props.apple().webRedirectUri() : "");
    return result;
  }
}
