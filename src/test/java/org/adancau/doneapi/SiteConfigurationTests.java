package org.adancau.doneapi;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import org.adancau.doneapi.config.AppProperties;
import org.adancau.doneapi.sharing.SharingProperties;
import org.adancau.doneapi.site.*;
import org.junit.jupiter.api.Test;
class SiteConfigurationTests {
  final SharingProperties sharing=new SharingProperties(false,Duration.ofDays(7),10000);
  AppProperties props(String web,String redirect) {
    return new AppProperties(null,new AppProperties.Apple(true,"ro.done.app","team","key","file","secret","keys","token","revoke",web,redirect),null);
  }
  SiteProperties site(boolean mock,String origin,String support) {
    return new SiteProperties(mock,origin,"Test Operator Ltd","123 Main Street",support,"privacy@done.app","EU","Hosting Ltd",30);
  }
  @Test void mockSiteIsLocalOnlyAndProductionRejectsPlaceholders() {
    assertDoesNotThrow(()->SiteConfiguration.validate(site(true,"http://127.0.0.1:8080","support@done.example"),sharing,props("",""),false));
    assertThrows(IllegalStateException.class,()->SiteConfiguration.validate(site(true,"https://done.app","support@done.app"),sharing,props("",""),true));
    assertThrows(IllegalStateException.class,()->SiteConfiguration.validate(site(false,"https://done.app","support@done.example"),sharing,props("",""),true));
    assertDoesNotThrow(()->SiteConfiguration.validate(site(false,"https://done.app","support@done.app"),sharing,props("",""),true));
  }
  @Test void publicOriginCannotInjectPathsOrUntrustedRedirects() {
    for(String origin:java.util.List.of("http://done.app","https://user@done.app","https://done.app/path","https://done.app?next=evil","https://done.app#fragment"))
      assertThrows(IllegalStateException.class,()->SiteConfiguration.validate(site(false,origin,"support@done.app"),sharing,props("",""),true));
    assertThrows(IllegalStateException.class,()->SiteConfiguration.validate(site(false,"https://done.app","support@done.app"),sharing,props("ro.done.web","https://evil.app/delete-account"),true));
    assertDoesNotThrow(()->SiteConfiguration.validate(site(false,"https://done.app","support@done.app"),sharing,props("ro.done.web","https://done.app/delete-account"),true));
  }
}
