package dev.ocaina.tendwise.handoff;

import dev.ocaina.tendwise.business.Business;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.business.OpeningHours;
import dev.ocaina.tendwise.conversation.ConversationService;
import dev.ocaina.tendwise.job.Job;
import dev.ocaina.tendwise.job.JobRepository;
import dev.ocaina.tendwise.realtime.EventPublisher;
import dev.ocaina.tendwise.shared.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HandoffService {

  private final BusinessRepository businesses;
  private final HandoffRepository handoffs;
  private final ConversationService conversations;
  private final JobRepository jobs;
  private final EventPublisher events;

  public HandoffService(
      BusinessRepository businesses,
      HandoffRepository handoffs,
      ConversationService conversations,
      JobRepository jobs,
      EventPublisher events) {
    this.businesses = businesses;
    this.handoffs = handoffs;
    this.conversations = conversations;
    this.jobs = jobs;
    this.events = events;
  }

  /**
   * Creates an OPEN handoff and, in the same transaction (T41), moves its conversation from AI to
   * WAITING with a SYSTEM message and queues the AUTO_ACK job. The job runs right away when the
   * business is closed, otherwise after the business's autoAckMinutes. The panel gets
   * handoff.created (T40) after the commit.
   *
   * <p>404 if the business does not exist, or if the conversation is not in core.conversations for
   * this business (an unknown id and another business's conversation look the same). 409
   * (ConversationConflictException) if the conversation is not in AI.
   */
  @Transactional
  public HandoffResponse create(CreateHandoffRequest request) {
    Business business = findBusiness(request.businessSlug());
    if (!conversations.exists(request.conversationId(), business.getId())) {
      throw new NotFoundException(
          "Conversation not found", "No conversation with this id in this business.");
    }
    Handoff handoff =
        handoffs.save(
            new Handoff(
                business.getId(), request.conversationId(), request.reason(), request.summary()));
    events.handoffCreated(business.getId(), request.conversationId(), handoff.getId());
    // Throws when the conversation is no longer in AI: the transaction rolls back, so no handoff
    // is left behind.
    conversations.handOff(
        request.conversationId(), business.getId(), request.reason(), handoff.getId());
    jobs.save(
        Job.autoAck(
            business.getId(), request.conversationId(), handoff.getId(), autoAckTime(business)));
    return HandoffResponse.from(handoff);
  }

  /** Now when the business is closed (nobody will see the handoff soon), else in N minutes. */
  private static Instant autoAckTime(Business business) {
    Instant now = Instant.now();
    if (!OpeningHours.of(business).isOpenAt(now)) {
      return now;
    }
    return now.plus(Duration.ofMinutes(business.autoAckMinutes()));
  }

  /** Handoffs of the business, oldest first. A null status means "all statuses". */
  @Transactional(readOnly = true)
  public List<HandoffResponse> list(String businessSlug, HandoffStatus status) {
    Long businessId = findBusiness(businessSlug).getId();
    List<Handoff> found =
        status == null
            ? handoffs.findByBusinessIdOrderByCreatedAtAsc(businessId)
            : handoffs.findByBusinessIdAndStatusOrderByCreatedAtAsc(businessId, status);
    return found.stream().map(HandoffResponse::from).toList();
  }

  private Business findBusiness(String slug) {
    return businesses
        .findBySlug(slug)
        .orElseThrow(
            () -> new NotFoundException("Business not found", "No business with this slug."));
  }
}
