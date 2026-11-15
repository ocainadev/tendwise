package dev.ocaina.tendwise.conversation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Every query takes a businessId, so one business never sees another's messages. */
public interface MessageRepository extends JpaRepository<Message, Long> {

  List<Message> findByConversationIdAndBusinessIdOrderByIdAsc(UUID conversationId, Long businessId);

  /** What the customer may see (no internal messages) after a message id, oldest first (T38). */
  @Query(
      """
      SELECT m FROM Message m
      WHERE m.conversationId = :conversationId AND m.businessId = :businessId
        AND m.internal = false AND m.id > :afterId
      ORDER BY m.id ASC
      """)
  List<Message> findVisibleAfter(UUID conversationId, Long businessId, long afterId, Limit limit);

  /** Customer messages since a moment: the per-conversation rate limit (T38). */
  @Query(
      """
      SELECT COUNT(m) FROM Message m
      WHERE m.conversationId = :conversationId AND m.businessId = :businessId
        AND m.sender = dev.ocaina.tendwise.conversation.MessageSender.CUSTOMER
        AND m.createdAt > :since
      """)
  long countCustomerMessagesSince(UUID conversationId, Long businessId, Instant since);
}
