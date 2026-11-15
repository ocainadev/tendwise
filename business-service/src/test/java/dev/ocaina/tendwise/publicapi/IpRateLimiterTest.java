package dev.ocaina.tendwise.publicapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class IpRateLimiterTest {

  /** A clock the test moves by hand. */
  private static final class TestClock extends Clock {
    private Instant now = Instant.parse("2026-11-13T12:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  private final TestClock clock = new TestClock();
  private final IpRateLimiter limiter = new IpRateLimiter(3, Duration.ofMinutes(1), clock);

  @Test
  void acceptsUpToTheLimitInsideTheWindow() {
    assertThat(limiter.tryAcquire("203.0.113.1")).isTrue();
    assertThat(limiter.tryAcquire("203.0.113.1")).isTrue();
    assertThat(limiter.tryAcquire("203.0.113.1")).isTrue();
    assertThat(limiter.tryAcquire("203.0.113.1")).isFalse();
  }

  @Test
  void eachIpHasItsOwnCount() {
    for (int i = 0; i < 3; i++) {
      limiter.tryAcquire("203.0.113.1");
    }
    assertThat(limiter.tryAcquire("203.0.113.1")).isFalse();
    assertThat(limiter.tryAcquire("203.0.113.2")).isTrue();
  }

  @Test
  void theWindowSlides() {
    limiter.tryAcquire("203.0.113.1");
    clock.advance(Duration.ofSeconds(30));
    limiter.tryAcquire("203.0.113.1");
    limiter.tryAcquire("203.0.113.1");
    assertThat(limiter.tryAcquire("203.0.113.1")).isFalse();

    // The first request leaves the window; the other two are still inside it.
    clock.advance(Duration.ofSeconds(31));
    assertThat(limiter.tryAcquire("203.0.113.1")).isTrue();
    assertThat(limiter.tryAcquire("203.0.113.1")).isFalse();
  }

  @Test
  void rejectedRequestsDoNotExtendTheBlock() {
    for (int i = 0; i < 10; i++) {
      limiter.tryAcquire("203.0.113.1");
    }
    clock.advance(Duration.ofMinutes(1));
    assertThat(limiter.tryAcquire("203.0.113.1")).isTrue();
  }
}
