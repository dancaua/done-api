package org.adancau.doneapi.common;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class Crypto {
  private static final SecureRandom RANDOM = new SecureRandom();

  private Crypto() {}

  public static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
