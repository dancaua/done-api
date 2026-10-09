package org.adancau.doneapi.common;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController
public class LocalizationController {
  private final MessageCatalog catalog;
  public LocalizationController(MessageCatalog catalog) { this.catalog=catalog; }
  @GetMapping("/api/v1/localizations/{language}")
  public Map<String,String> get(@PathVariable String language) { return catalog.values(language); }
}
