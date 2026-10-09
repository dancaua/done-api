package org.adancau.doneapi.common;

import java.util.*;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class MessageCatalog {
    private final Map<String, Map<String, String>> catalogs = new HashMap<>();

    public MessageCatalog(JsonMapper json) throws java.io.IOException {
        for (String language : List.of("en", "ro", "es", "it", "fr", "de", "pl", "hi", "ja")) {
            try (var stream = new ClassPathResource("localizations/" + language + ".json").getInputStream()) {
                var tree = json.readTree(stream);
                var values = new LinkedHashMap<String, String>();
                tree.properties().forEach(e -> values.put(e.getKey(), e.getValue().asText()));
                catalogs.put(language, Collections.unmodifiableMap(values));
            }
        }
    }

    public Map<String, String> values(String language) {
        if (!catalogs.containsKey(language)) throw ApiException.invalid("Unsupported language.");
        return catalogs.get(language);
    }

    public boolean contains(String key) {
        return catalogs.get("en").containsKey(key);
    }

    public String text(String language, String key, List<String> arguments) {
        String value = values(language).getOrDefault(key, catalogs.get("en").getOrDefault(key, key));
        // One pass: literal user arguments cannot become replacement placeholders.
        var pattern = java.util.regex.Pattern.compile("\\{(\\d+)\\}").matcher(value);
        var out = new StringBuilder();
        while (pattern.find()) {
            int n = Integer.parseInt(pattern.group(1));
            pattern.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(n < arguments.size() ? arguments.get(n) : pattern.group()));
        }
        pattern.appendTail(out);
        return out.toString();
    }
}
