package dev.ocaina.tendwise.handoff;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Every query takes a businessId, so one business never sees another's handoffs. */
public interface HandoffRepository extends JpaRepository<Handoff, Long> {

  List<Handoff> findByBusinessIdOrderByCreatedAtAsc(Long businessId);

  List<Handoff> findByBusinessIdAndStatusOrderByCreatedAtAsc(Long businessId, HandoffStatus status);

  Optional<Handoff> findByIdAndBusinessId(Long id, Long businessId);

  /** The handoffs of one conversation, oldest first (the panel's conversation detail, T43). */
  List<Handoff> findByBusinessIdAndConversationIdOrderByCreatedAtAsc(
      Long businessId, UUID conversationId);

  /**
   * Staff took the conversation (take over or assign, T44): its handoffs that are still open move
   * to IN_PROGRESS with the new assignee. A later assign moves the assignee and assigned_at again.
   * Returns the number changed; 0 is fine (a conversation taken from the AI has no handoff).
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      UPDATE Handoff h
      SET h.status = dev.ocaina.tendwise.handoff.HandoffStatus.IN_PROGRESS,
          h.assigneeUserId = :assigneeUserId, h.assignedAt = :now, h.updatedAt = :now
      WHERE h.conversationId = :conversationId AND h.businessId = :businessId
        AND h.status IN (dev.ocaina.tendwise.handoff.HandoffStatus.OPEN,
                         dev.ocaina.tendwise.handoff.HandoffStatus.IN_PROGRESS)
      """)
  int assignOfConversation(UUID conversationId, Long businessId, Long assigneeUserId, Instant now);

  /**
   * The conversation was resolved or handed back to the AI (T44): its open handoffs end. Returns
   * the number changed; 0 is fine (a conversation the AI resolved has no handoff).
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      UPDATE Handoff h
      SET h.status = dev.ocaina.tendwise.handoff.HandoffStatus.RESOLVED,
          h.resolvedAt = :now, h.resolvedBy = :resolvedBy, h.updatedAt = :now
      WHERE h.conversationId = :conversationId AND h.businessId = :businessId
        AND h.status IN (dev.ocaina.tendwise.handoff.HandoffStatus.OPEN,
                         dev.ocaina.tendwise.handoff.HandoffStatus.IN_PROGRESS)
      """)
  int resolveOfConversation(UUID conversationId, Long businessId, Long resolvedBy, Instant now);

  /**
   * Staff sent the customer a message: sets first_response_at of the handoff in progress, only the
   * first time (the response-time metric).
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      UPDATE Handoff h SET h.firstResponseAt = :now
      WHERE h.conversationId = :conversationId AND h.businessId = :businessId
        AND h.status = dev.ocaina.tendwise.handoff.HandoffStatus.IN_PROGRESS
        AND h.firstResponseAt IS NULL
      """)
  int recordFirstResponse(UUID conversationId, Long businessId, Instant now);
}
