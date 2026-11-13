package dev.ocaina.tendwise.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderServiceTest {

  @Test
  void phoneMatchIgnoresFormatting() {
    assertThat(OrderService.phoneEndsWith("+1 212-555-0102", "0102")).isTrue();
    assertThat(OrderService.phoneEndsWith("+55 11 90000-0201", "0201")).isTrue();
  }

  @Test
  void phoneMatchRejectsOtherDigits() {
    assertThat(OrderService.phoneEndsWith("+1 212-555-0102", "0101")).isFalse();
    assertThat(OrderService.phoneEndsWith("+1 212-555-0102", "5550")).isFalse();
  }

  @Test
  void phoneWithFewerThanFourDigitsNeverMatches() {
    assertThat(OrderService.phoneEndsWith("12", "0012")).isFalse();
  }
}
