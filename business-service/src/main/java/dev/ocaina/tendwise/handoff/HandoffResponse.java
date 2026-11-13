package dev.ocaina.tendwise.handoff;

import java.time.Instant;
import java.util.UUID;

/** What the API returns for a handoff. businessId stays internal: callers use the slug. */
public record HandoffResponse(
    Long id,
    UUID conversationId,
    HandoffReason reason,
    String summary,
    HandoffStatus status,
    Instant createdAt,
    Instant updatedAt) {

  static HandoffResponse from(Handoff handoff) {
    return new HandoffResponse(
        handoff.getId(),
        handoff.getConversationId(),
        handoff.getReason(),
        handoff.getSummary(),
        handoff.getStatus(),
        handoff.getCreatedAt(),
        handoff.getUpdatedAt());
  }
}
