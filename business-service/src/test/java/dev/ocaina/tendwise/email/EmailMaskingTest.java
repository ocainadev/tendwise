package dev.ocaina.tendwise.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailMaskingTest {

  @Test
  void keepsTheFirstLetterAndTheDomain() {
    assertThat(EmailMasking.mask("joana@example.com")).isEqualTo("j***@example.com");
  }

  @Test
  void hidesEverythingThatIsNotAnEmail() {
    assertThat(EmailMasking.mask("joana")).isEqualTo("***");
    assertThat(EmailMasking.mask(null)).isEqualTo("***");
    assertThat(EmailMasking.mask("@example.com")).isEqualTo("***@example.com");
  }
}
