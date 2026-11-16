package dev.ocaina.tendwise.inbox;

import dev.ocaina.tendwise.conversation.Channel;
import dev.ocaina.tendwise.conversation.ConversationState;
import dev.ocaina.tendwise.conversation.MessageSender;
import dev.ocaina.tendwise.handoff.HandoffReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One page of the inbox list (GET /api/v1/panel/{slug}/conversations).
 *
 * @param nextCursor pass it as {@code cursor} to get the next page; null on the last page
 * @param counts the tab badges. They ignore the request's filters, so every page has the same ones
 */
public record ConversationListResponse(List<Item> items, String nextCursor, TabCounts counts) {

  /**
   * One row of the list.
   *
   * @param assignee null when nobody took the conversation
   * @param lastMessage the newest customer, AI or staff message (SYSTEM messages are skipped); null
   *     when there is none yet
   * @param unreadCount customer messages since staff last opened it (conversations.unread_by_staff)
   * @param handoffReason reason of the conversation's handoff that is not resolved yet, if any
   * @param waitingSince when the conversation moved to WAITING; null in every other state
   */
  public record Item(
      UUID id,
      ConversationState state,
      Channel channel,
      String language,
      Assignee assignee,
      LastMessage lastMessage,
      Instant lastMessageAt,
      int unreadCount,
      HandoffReason handoffReason,
      Instant waitingSince) {}

  /**
   * A short preview of a message.
   *
   * @param preview the first {@value InboxQueries#PREVIEW_LENGTH} characters of the body
   * @param internal true for a staff-only note
   */
  public record LastMessage(MessageSender sender, String preview, boolean internal) {}

  /**
   * Badges of the inbox tabs: "Precisa de você" (WAITING), "Comigo" (HUMAN, assigned to the user
   * who asks) and "IA atendendo" (AI). Resolved conversations are not counted.
   */
  public record TabCounts(TabCount waiting, TabCount mine, TabCount ai) {}

  /**
   * @param conversations conversations in the tab
   * @param unread how many of them have unread customer messages
   */
  public record TabCount(long conversations, long unread) {}
}
