package dev.ocaina.tendwise.inbox;

import dev.ocaina.tendwise.conversation.Channel;
import dev.ocaina.tendwise.conversation.ConversationState;
import dev.ocaina.tendwise.conversation.MessageSender;
import dev.ocaina.tendwise.handoff.HandoffReason;
import dev.ocaina.tendwise.handoff.HandoffStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One conversation as staff see it (GET /api/v1/panel/{slug}/conversations/{id}): every message,
 * internal notes included, and its handoffs. Satisfaction is added with its table (T61).
 *
 * @param waitingSince when the conversation moved to WAITING; null in every other state
 * @param messages oldest first
 * @param handoffs oldest first
 */
public record ConversationDetailResponse(
    UUID id,
    ConversationState state,
    Channel channel,
    String language,
    Assignee assignee,
    int unreadCount,
    Instant createdAt,
    Instant lastMessageAt,
    Instant resolvedAt,
    Instant waitingSince,
    List<MessageItem> messages,
    List<HandoffItem> handoffs) {

  /**
   * @param authorUserId and authorName are set only for HUMAN messages
   * @param internal true = staff only, never sent to the customer
   * @param metadata AI: intent, language, sources, orderCode. SYSTEM: event and its details
   */
  public record MessageItem(
      long id,
      MessageSender sender,
      Long authorUserId,
      String authorName,
      String body,
      boolean internal,
      Map<String, Object> metadata,
      Instant createdAt) {}

  /** A handoff of this conversation, with the AI's summary for staff. */
  public record HandoffItem(
      long id,
      HandoffReason reason,
      String summary,
      HandoffStatus status,
      Instant createdAt,
      Instant updatedAt) {}
}
