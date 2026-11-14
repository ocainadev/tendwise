package dev.ocaina.tendwise.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordConfigTest {

  private final PasswordEncoder encoder = new PasswordConfig().passwordEncoder();

  @Test
  void hashIsArgon2idAndNotThePlainPassword() {
    String hash = encoder.encode("correct horse battery staple");

    assertThat(hash).startsWith("$argon2id$").doesNotContain("correct horse battery staple");
    assertThat(hash).contains("m=19456,t=2,p=1");
  }

  @Test
  void hashVerifiesOnlyTheRightPassword() {
    String hash = encoder.encode("correct horse battery staple");

    assertThat(encoder.matches("correct horse battery staple", hash)).isTrue();
    assertThat(encoder.matches("wrong password", hash)).isFalse();
  }

  @Test
  void samePasswordGetsADifferentSaltEachTime() {
    assertThat(encoder.encode("same password")).isNotEqualTo(encoder.encode("same password"));
  }
}
