package dev.ocaina.tendwise.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Random tokens (refresh and CSRF) and the hash under which a refresh token is stored. */
final class SecureTokens {

  private static final SecureRandom RANDOM = new SecureRandom();

  private SecureTokens() {}

  /** 32 random bytes (256 bits) in base64url: 43 characters, safe in a cookie. */
  static String generate() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * Hex SHA-256. A plain hash is enough (no salt, no Argon2): the token is already 256 random bits,
   * so it cannot be guessed from its hash.
   */
  static String sha256Hex(String token) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available in the JDK", e);
    }
  }
}
