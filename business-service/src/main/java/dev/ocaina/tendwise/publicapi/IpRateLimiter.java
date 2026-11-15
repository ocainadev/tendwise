package dev.ocaina.tendwise.publicapi;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * At most {@code limit} public POST requests per IP in any {@code window} (sliding window).
 *
 * <p>In memory, per instance (docs/architecture.md, "Public endpoints"): enough while one instance
 * runs. The per-conversation limit is the one counted in the database. IPs are only kept here,
 * never logged or stored.
 */
@Component
class IpRateLimiter {

  /** Above this many IPs, the ones with no request inside the window are forgotten. */
  private static final int CLEANUP_THRESHOLD = 10_000;

  private final int limit;
  private final long windowMillis;
  private final Clock clock;

  /** Times (epoch millis) of the accepted requests inside the window, oldest first, per IP. */
  private final Map<String, Deque<Long>> requests = new HashMap<>();

  @Autowired
  IpRateLimiter(
      @Value("${tendwise.public-api.ip-limit}") int limit,
      @Value("${tendwise.public-api.ip-window}") Duration window) {
    this(limit, window, Clock.systemUTC());
  }

  IpRateLimiter(int limit, Duration window, Clock clock) {
    this.limit = limit;
    this.windowMillis = window.toMillis();
    this.clock = clock;
  }

  /** Records the request and returns true, or returns false when the IP is over its limit. */
  synchronized boolean tryAcquire(String ip) {
    long now = clock.millis();
    long windowStart = now - windowMillis;
    if (requests.size() > CLEANUP_THRESHOLD) {
      requests.values().removeIf(times -> times.peekLast() <= windowStart);
    }

    Deque<Long> times = requests.computeIfAbsent(ip, key -> new ArrayDeque<>());
    while (!times.isEmpty() && times.peekFirst() <= windowStart) {
      times.pollFirst();
    }
    if (times.size() >= limit) {
      return false;
    }
    times.addLast(now);
    return true;
  }
}
