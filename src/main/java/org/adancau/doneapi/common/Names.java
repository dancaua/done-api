package org.adancau.doneapi.common;

import java.text.Normalizer;
import java.util.regex.Pattern;

public final class Names {
  private static final Pattern GRAPHEME = Pattern.compile("\\X");
  private Names() {}
  public static String clean(String value, int max, boolean required) {
    String result = value == null ? "" : Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
    long count = GRAPHEME.matcher(result).results().count();
    if ((required && result.isEmpty()) || count > max || result.codePoints().anyMatch(Character::isISOControl))
      throw ApiException.invalid("Invalid name: maximum " + max + " visible characters.");
    return result;
  }
}
