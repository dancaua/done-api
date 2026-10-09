package org.adancau.doneapi.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.adancau.doneapi.config.AppProperties;
import org.springframework.stereotype.Component;

@Component
public class AppleTokenCipher {
  private final byte[] key;

  public AppleTokenCipher(AppProperties p) {
    if (!p.apple().enabled()) {
      key = null;
      return;
    }
    try {
      key = Base64.getDecoder().decode(p.apple().encryptionKey());
      if (key.length != 32) throw new IllegalArgumentException();
    } catch (Exception e) {
      throw new IllegalStateException(
          "APPLE_ENCRYPTION_KEY must contain 32 base64-encoded random bytes.");
    }
  }

  public String encrypt(String token) {
    try {
      byte[] iv = new byte[12];
      new SecureRandom().nextBytes(iv);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      return Base64.getEncoder().encodeToString(iv)
          + "."
          + Base64.getEncoder()
              .encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot encrypt Apple credential", e);
    }
  }

  public String decrypt(String token) {
    try {
      var parts = token.split("[.]");
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
      return new String(
          cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot decrypt Apple credential", e);
    }
  }
}
