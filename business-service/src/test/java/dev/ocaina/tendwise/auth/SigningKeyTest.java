package dev.ocaina.tendwise.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ocaina.tendwise.TestcontainersConfiguration;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Reading AUTH_JWT_PRIVATE_KEY. Plain unit tests: no Spring, no database. */
class SigningKeyTest {

  private static final String KEY = TestcontainersConfiguration.TEST_SIGNING_KEY;

  @Test
  void aMissingKeyStopsTheServiceWithAClearMessage() {
    assertThatThrownBy(() -> SigningKey.parse(""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_JWT_PRIVATE_KEY must be set");
    assertThatThrownBy(() -> new AccessTokens(null))
        .hasMessageContaining("AUTH_JWT_PRIVATE_KEY must be set");
  }

  @Test
  void anInvalidKeyIsRejected() {
    assertThatThrownBy(() -> SigningKey.parse("not base64!"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a valid");
  }

  @Test
  void aKeyOfAnotherTypeIsRejected() throws Exception {
    byte[] rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate().getEncoded();

    assertThatThrownBy(() -> SigningKey.parse(Base64.getEncoder().encodeToString(rsa)))
        .hasMessageContaining("not a valid");
  }

  @Test
  void pemAndPlainBase64GiveTheSameKey() {
    String pem = "-----BEGIN PRIVATE KEY-----\n" + KEY + "\n-----END PRIVATE KEY-----\n";

    assertThat(SigningKey.parse(pem).keyId()).isEqualTo(SigningKey.parse(KEY).keyId());
  }

  @Test
  void theDerivedPublicKeyVerifiesTheTokens() {
    AccessTokens tokens = new AccessTokens(KEY);
    String token =
        tokens.issue(new AuthenticatedUser(7, null, java.util.List.of()), java.time.Instant.now());

    assertThat(tokens.verify(token))
        .hasValueSatisfying(user -> assertThat(user.userId()).isEqualTo(7));
  }

  @Test
  void aTokenSignedWithAnotherKeyIsRejected() throws Exception {
    byte[] other =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded();
    String foreign =
        new AccessTokens(Base64.getEncoder().encodeToString(other))
            .issue(new AuthenticatedUser(7, null, java.util.List.of()), java.time.Instant.now());

    assertThat(new AccessTokens(KEY).verify(foreign)).isEmpty();
  }
}
