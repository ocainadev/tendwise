package dev.ocaina.tendwise.conversation;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Every query takes a businessId, so one business never sees or changes another's conversations.
 *
 * <p>The updates are conditional: they return the number of rows changed, and 0 means the
 * conversation was not in one of the expected states (or does not belong to the business). They
 * clear the persistence context, so a later read sees the new values.
 */
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

  Optional<Conversation> findByIdAndBusinessId(UUID id, Long businessId);

  /**
   * Reads the conversation and locks its row until the transaction ends (SELECT … FOR UPDATE), so a
   * new message and a state change of the same conversation never run at the same time.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT c FROM Conversation c WHERE c.id = :id AND c.businessId = :businessId")
  Optional<Conversation> findForUpdate(UUID id, Long businessId);

  /** Moves to a new state and sets the assignee. resolvedAt is cleared. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      UPDATE Conversation c
      SET c.state = :to, c.assigneeUserId = :assigneeUserId, c.resolvedAt = NULL,
          c.version = c.version + 1
      WHERE c.id = :id AND c.businessId = :businessId AND c.state IN :from
      """)
  int changeState(
      UUID id,
      Long businessId,
      Collection<ConversationState> from,
      ConversationState to,
      Long assigneeUserId);

  /** Moves to RESOLVED. The assignee is kept, so the inbox still shows who handled it. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      UPDATE Conversation c
      SET c.state = dev.ocaina.tendwise.conversation.ConversationState.RESOLVED, c.resolvedAt = :resolvedAt,
          c.version = c.version + 1
      WHERE c.id = :id AND c.businessId = :businessId AND c.state IN :from
      """)
  int resolve(UUID id, Long businessId, Collection<ConversationState> from, Instant resolvedAt);

  /** A message was stored: moves last_message_at and adds unread (1 for customer messages). */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      UPDATE Conversation c
      SET c.lastMessageAt = :at, c.unreadByStaff = c.unreadByStaff + :unread
      WHERE c.id = :id AND c.businessId = :businessId
      """)
  int recordMessage(UUID id, Long businessId, Instant at, int unread);
}
