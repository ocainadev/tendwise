package dev.ocaina.tendwise.inbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * At most one staff.typing per staff member and conversation every 3 seconds (T44). In memory per
 * instance, like the public API's per-IP limit: a second instance may let one more through, which
 * only means one more "typing…" hint.
 */
@Component
class StaffTypingLimiter {

  static final Duration INTERVAL = Duration.ofSeconds(3);

  /** Above this many entries, the expired ones are dropped, so the map does not grow forever. */
  private static final int CLEANUP_SIZE = 10_000;

  private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();

  /** True when this user may send staff.typing for this conversation now (and records it). */
  boolean tryAcquire(UUID conversationId, long userId) {
    Instant now = Instant.now();
    if (lastSent.size() > CLEANUP_SIZE) {
      lastSent.values().removeIf(sentAt -> sentAt.plus(INTERVAL).isBefore(now));
    }
    String key = conversationId + ":" + userId;
    boolean[] allowed = {false};
    lastSent.compute(
        key,
        (k, sentAt) -> {
          if (sentAt == null || !now.isBefore(sentAt.plus(INTERVAL))) {
            allowed[0] = true;
            return now;
          }
          return sentAt;
        });
    return allowed[0];
  }
}
