package dev.ocaina.tendwise.publicapi;

import dev.ocaina.tendwise.conversation.Message;
import dev.ocaina.tendwise.conversation.MessageSender;
import java.time.Instant;

/**
 * A message as the customer sees it. No metadata and no user ids: those are for staff only.
 *
 * @param authorName first name of the staff member; only for HUMAN messages, otherwise null
 */
public record PublicMessage(
    Long id, MessageSender sender, String authorName, String body, Instant createdAt) {

  static PublicMessage from(Message message, String authorName) {
    return new PublicMessage(
        message.getId(),
        message.getSender(),
        authorName,
        message.getBody(),
        message.getCreatedAt());
  }
}
