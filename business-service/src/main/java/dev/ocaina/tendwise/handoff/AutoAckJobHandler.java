package dev.ocaina.tendwise.handoff;

import dev.ocaina.tendwise.business.Business;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.business.OpeningHours;
import dev.ocaina.tendwise.conversation.ConversationService;
import dev.ocaina.tendwise.conversation.Message;
import dev.ocaina.tendwise.job.Job;
import dev.ocaina.tendwise.job.JobHandler;
import dev.ocaina.tendwise.job.JobType;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs an AUTO_ACK job (T41): tells a customer whose handoff is still waiting, once, that the team
 * was notified. The job worker (T39) calls {@link #run(Job)} for every due AUTO_ACK job and marks
 * the job DONE when it returns, whether a message was sent or not.
 *
 * <p>Nothing is sent when staff already took over, released or resolved the conversation, when the
 * handoff is no longer OPEN, or when this handoff's auto-ack was already sent. So running the same
 * job twice (a retry after a crash) still sends one message.
 */
@Component
public class AutoAckJobHandler implements JobHandler {

  private final BusinessRepository businesses;
  private final HandoffRepository handoffs;
  private final ConversationService conversations;

  public AutoAckJobHandler(
      BusinessRepository businesses,
      HandoffRepository handoffs,
      ConversationService conversations) {
    this.businesses = businesses;
    this.handoffs = handoffs;
    this.conversations = conversations;
  }

  @Override
  public JobType type() {
    return JobType.AUTO_ACK;
  }

  /** Called by the job worker. */
  @Override
  @Transactional
  public void run(Job job) {
    handle(job);
  }

  /** Returns the message sent, or empty when nothing had to be sent. */
  @Transactional
  public Optional<Message> handle(Job job) {
    if (job.getType() != JobType.AUTO_ACK) {
      throw new IllegalArgumentException("Not an AUTO_ACK job: " + job.getType());
    }
    Map<String, Object> payload = job.getPayload();
    Long businessId = ((Number) payload.get("businessId")).longValue();
    UUID conversationId = UUID.fromString((String) payload.get("conversationId"));
    Long handoffId = ((Number) payload.get("handoffId")).longValue();

    boolean handoffStillOpen =
        handoffs
            .findByIdAndBusinessId(handoffId, businessId)
            .filter(handoff -> handoff.getConversationId().equals(conversationId))
            .filter(handoff -> handoff.getStatus() == HandoffStatus.OPEN)
            .isPresent();
    Optional<Business> business = businesses.findById(businessId);
    if (!handoffStillOpen || business.isEmpty()) {
      return Optional.empty();
    }
    return conversations.addAutoAck(
        conversationId, businessId, handoffId, openingDetails(business.get(), Instant.now()));
  }

  /**
   * businessOpen, and opensAt (ISO date-time in the business's time zone) when closed, so the
   * widget can say "we reply from 9:00".
   */
  private static Map<String, Object> openingDetails(Business business, Instant now) {
    OpeningHours hours = OpeningHours.of(business);
    Map<String, Object> details = new HashMap<>();
    boolean open = hours.isOpenAt(now);
    details.put("businessOpen", open);
    if (!open) {
      hours
          .nextOpeningAfter(now)
          .ifPresent(opensAt ->
              details.put("opensAt", opensAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
    }
    return details;
  }
}
