package dev.ocaina.tendwise.handoff;

import java.time.Instant;
import java.util.Collection;
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
   * Moves the conversation's handoffs that are in one of the from statuses (T41: the handoff
   * follows its conversation). Returns the number of handoffs changed, 0 is fine (a conversation
   * without a handoff, e.g. one the AI resolved).
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      UPDATE Handoff h SET h.status = :to, h.updatedAt = :now
      WHERE h.conversationId = :conversationId AND h.businessId = :businessId
        AND h.status IN :from
      """)
  int changeStatusOfConversation(
      UUID conversationId,
      Long businessId,
      Collection<HandoffStatus> from,
      HandoffStatus to,
      Instant now);
}
