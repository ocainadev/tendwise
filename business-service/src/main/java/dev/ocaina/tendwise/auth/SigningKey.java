package dev.ocaina.tendwise.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;

/**
 * The Ed25519 key pair that signs access tokens, read from AUTH_JWT_PRIVATE_KEY.
 *
 * <p>Only the private key is configured. The public key is derived from it, so the two can never
 * get out of sync.
 *
 * @param publicKeyX the raw 32-byte public key in base64url: the "x" member of the JWK
 * @param keyId the JWK thumbprint (RFC 7638), sent as "kid" in every token header
 */
record SigningKey(PrivateKey privateKey, PublicKey publicKey, String publicKeyX, String keyId) {

  /** DER header of an X.509 SubjectPublicKeyInfo for Ed25519; the 32 raw key bytes follow it. */
  private static final byte[] X509_ED25519_PREFIX =
      HexFormat.of().parseHex("302a300506032b6570032100");

  private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

  /**
   * Reads a base64 PKCS#8 Ed25519 private key, as printed by {@code openssl genpkey -algorithm
   * ed25519 -outform DER | base64}. A PEM block (with BEGIN/END lines) works too.
   */
  static SigningKey parse(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      throw new IllegalStateException(
          "AUTH_JWT_PRIVATE_KEY must be set: a base64 Ed25519 private key (see the README).");
    }
    String base64 =
        encoded.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
    try {
      byte[] der = Base64.getDecoder().decode(base64);
      KeyFactory factory = KeyFactory.getInstance("Ed25519");
      EdECPrivateKey privateKey =
          (EdECPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));

      // The JDK cannot derive the public key from the private one; Bouncy Castle can.
      byte[] seed = privateKey.getBytes().orElseThrow();
      byte[] rawPublicKey =
          new Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().getEncoded();
      PublicKey publicKey =
          factory.generatePublic(new X509EncodedKeySpec(concat(X509_ED25519_PREFIX, rawPublicKey)));

      String x = BASE64URL.encodeToString(rawPublicKey);
      return new SigningKey(privateKey, publicKey, x, thumbprint(x));
    } catch (IllegalArgumentException | GeneralSecurityException | ClassCastException e) {
      throw new IllegalStateException(
          "AUTH_JWT_PRIVATE_KEY is not a valid base64 Ed25519 private key (PKCS#8).", e);
    }
  }

  /** RFC 7638: SHA-256 of the required JWK members, in this exact order and without spaces. */
  private static String thumbprint(String x) throws GeneralSecurityException {
    String json = "{\"crv\":\"Ed25519\",\"kty\":\"OKP\",\"x\":\"" + x + "\"}";
    byte[] hash =
        MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
    return BASE64URL.encodeToString(hash);
  }

  private static byte[] concat(byte[] first, byte[] second) {
    byte[] result = new byte[first.length + second.length];
    System.arraycopy(first, 0, result, 0, first.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }
}
