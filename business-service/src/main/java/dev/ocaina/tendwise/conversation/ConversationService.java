package dev.ocaina.tendwise.conversation;

import static dev.ocaina.tendwise.conversation.ConversationState.AI;
import static dev.ocaina.tendwise.conversation.ConversationState.HUMAN;
import static dev.ocaina.tendwise.conversation.ConversationState.RESOLVED;
import static dev.ocaina.tendwise.conversation.ConversationState.WAITING;
import static dev.ocaina.tendwise.handoff.HandoffStatus.IN_PROGRESS;
import static dev.ocaina.tendwise.handoff.HandoffStatus.OPEN;

import dev.ocaina.tendwise.handoff.HandoffReason;
import dev.ocaina.tendwise.handoff.HandoffRepository;
import dev.ocaina.tendwise.handoff.HandoffStatus;
import dev.ocaina.tendwise.job.Job;
import dev.ocaina.tendwise.job.JobRepository;
import dev.ocaina.tendwise.realtime.EventPublisher;
import dev.ocaina.tendwise.shared.NotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The conversation state machine (docs/architecture.md, "Conversations and message routing").
 *
 * <p>Every state change is one conditional UPDATE (… WHERE state IN (expected) AND business_id = …)
 * in a transaction that also writes a SYSTEM message. If the UPDATE changes no row, someone else
 * changed the state first: {@link ConversationConflictException}, and nothing is written.
 *
 * <p>A handoff's status follows its conversation in the same transaction (T41): WAITING = OPEN,
 * HUMAN = IN_PROGRESS, RESOLVED or back to AI = RESOLVED.
 *
 * <p>Live events (T40): every stored message sends message.created, every state change sends
 * conversation.state_changed, and an assignee change sends conversation.assigned. They are sent in
 * the same transaction, so PostgreSQL delivers them only after it commits ({@link EventPublisher}).
 *
 * <p>An unknown id and an id of another business both throw the same {@link NotFoundException}.
 */
@Service
public class ConversationService {

  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final JobRepository jobs;
  private final HandoffRepository handoffs;
  private final EventPublisher events;

  public ConversationService(
      ConversationRepository conversations,
      MessageRepository messages,
      JobRepository jobs,
      HandoffRepository handoffs,
      EventPublisher events) {
    this.conversations = conversations;
    this.messages = messages;
    this.jobs = jobs;
    this.handoffs = handoffs;
    this.events = events;
  }

  /** Starts a conversation in state AI. tokenHash is null for channels without a token. */
  @Transactional
  public Conversation start(
      Long businessId, Channel channel, String language, String customerRef, String tokenHash) {
    return conversations.save(
        new Conversation(businessId, channel, language, customerRef, tokenHash));
  }

  /**
   * Stores a customer message. In AI it also queues an AI_REPLY job in the same transaction. In
   * WAITING and HUMAN the message is only stored (staff answer). In RESOLVED the conversation first
   * reopens in AI, then continues as in AI.
   */
  @Transactional
  public Message addCustomerMessage(UUID conversationId, Long businessId, String body) {
    // The row lock keeps a state change from slipping in between reading the state and
    // deciding whether the AI answers.
    Conversation conversation = findForUpdate(conversationId, businessId);
    ConversationState state = conversation.getState();
    if (state == RESOLVED) {
      int updated =
          conversations.changeState(conversationId, businessId, Set.of(RESOLVED), AI, null);
      recordTransition(updated, conversationId, businessId, SystemEvent.REOPENED, Map.of());
      state = AI;
    }

    Message message = save(Message.customer(conversationId, businessId, body), 1);
    // Only the AI state queues an AI reply: in WAITING and HUMAN a person answers.
    if (state == AI) {
      jobs.save(Job.aiReply(businessId, conversationId, message.getId()));
    }
    return message;
  }

  /** True when the conversation exists in this business. */
  @Transactional(readOnly = true)
  public boolean exists(UUID conversationId, Long businessId) {
    return conversations.findByIdAndBusinessId(conversationId, businessId).isPresent();
  }

  /**
   * True while the AI is the one answering (state AI). Hook for the AI_REPLY worker (T39): a job
   * queued before a handoff or a takeover is skipped when this is false, so the AI is never even
   * asked while a person has the conversation. Unknown conversation: false.
   */
  @Transactional(readOnly = true)
  public boolean isAiAnswering(UUID conversationId, Long businessId) {
    return conversations
        .findByIdAndBusinessId(conversationId, businessId)
        .map(conversation -> conversation.getState() == AI)
        .orElse(false);
  }

  /**
   * Stores the AI's reply, but only while the conversation is still in AI. Empty means the reply
   * was dropped because staff took over meanwhile: the AI never talks over a person.
   */
  @Transactional
  public Optional<Message> addAiMessage(
      UUID conversationId, Long businessId, String body, Map<String, Object> metadata) {
    return addAiMessage(conversationId, businessId, body, metadata, false);
  }

  /**
   * Same, for the AI_REPLY job (T39). agentHandedOff: agent-service created a handoff during this
   * turn, so the conversation is already WAITING and the reply is the "a person will continue"
   * text. It is stored in WAITING too, and the state is left as it is. In HUMAN or RESOLVED the
   * reply is always dropped.
   */
  @Transactional
  public Optional<Message> addAiMessage(
      UUID conversationId,
      Long businessId,
      String body,
      Map<String, Object> metadata,
      boolean agentHandedOff) {
    ConversationState state = findForUpdate(conversationId, businessId).getState();
    boolean aiMayAnswer = state == AI || (agentHandedOff && state == WAITING);
    if (!aiMayAnswer) {
      return Optional.empty();
    }
    return Optional.of(save(Message.ai(conversationId, businessId, body, metadata), 0));
  }

  /**
   * AI → WAITING: the AI_REPLY job failed its last attempt (T39), so a person picks the
   * conversation up instead of the customer waiting forever. Writes an AI_FAILED SYSTEM message.
   * Empty, and nothing is written, when the conversation is no longer in AI (a handoff or staff got
   * there first) or is unknown: there is nothing left to rescue, so this never throws a conflict
   * and is safe to repeat.
   */
  @Transactional
  public Optional<Conversation> failAiReply(UUID conversationId, Long businessId) {
    int updated = conversations.changeState(conversationId, businessId, Set.of(AI), WAITING, null);
    if (updated == 0) {
      return Optional.empty();
    }
    Map<String, Object> metadata = Map.of("event", SystemEvent.AI_FAILED.name());
    save(Message.system(conversationId, businessId, SystemEvent.AI_FAILED, metadata), 0);
    Conversation conversation = find(conversationId, businessId);
    events.stateChanged(conversation);
    return Optional.of(conversation);
  }

  /**
   * AI → WAITING: the handoff handoffId (already stored, status OPEN) was created. The AI stops
   * answering until staff act.
   */
  @Transactional
  public Conversation handOff(
      UUID conversationId, Long businessId, HandoffReason reason, Long handoffId) {
    int updated = conversations.changeState(conversationId, businessId, Set.of(AI), WAITING, null);
    return recordTransition(
        updated,
        conversationId,
        businessId,
        SystemEvent.HANDOFF_CREATED,
        Map.of("reason", reason.name(), "handoffId", handoffId));
  }

  /** WAITING → HUMAN: the staff member userId takes over. Two at once: only one wins. */
  @Transactional
  public Conversation takeOver(UUID conversationId, Long businessId, Long userId) {
    int updated =
        conversations.changeState(conversationId, businessId, Set.of(WAITING), HUMAN, userId);
    Conversation conversation =
        recordTransition(
            updated, conversationId, businessId, SystemEvent.TAKEN_OVER, Map.of("userId", userId));
    syncHandoffs(conversationId, businessId, Set.of(OPEN), IN_PROGRESS);
    events.assigned(conversation);
    return conversation;
  }

  /** WAITING or HUMAN → AI: staff hand the conversation back to the AI. */
  @Transactional
  public Conversation release(UUID conversationId, Long businessId, Long userId) {
    int updated =
        conversations.changeState(conversationId, businessId, Set.of(WAITING, HUMAN), AI, null);
    Conversation conversation =
        recordTransition(
            updated, conversationId, businessId, SystemEvent.RELEASED, Map.of("userId", userId));
    syncHandoffs(conversationId, businessId, Set.of(OPEN, IN_PROGRESS), HandoffStatus.RESOLVED);
    // Back to the AI: nobody is assigned any more.
    events.assigned(conversation);
    return conversation;
  }

  /** AI, WAITING or HUMAN → RESOLVED: staff close the conversation. */
  @Transactional
  public Conversation resolve(UUID conversationId, Long businessId, Long userId) {
    int updated =
        conversations.resolve(
            conversationId, businessId, Set.of(AI, WAITING, HUMAN), Conversation.now());
    Conversation conversation =
        recordTransition(
            updated, conversationId, businessId, SystemEvent.RESOLVED, Map.of("userId", userId));
    syncHandoffs(conversationId, businessId, Set.of(OPEN, IN_PROGRESS), HandoffStatus.RESOLVED);
    return conversation;
  }

  /**
   * Stores the one automatic message of handoff handoffId (T41). Empty, and nothing is written,
   * when the conversation is no longer WAITING (staff took over, released or resolved it) or this
   * handoff's auto-ack was already sent. details: businessOpen and opensAt, chosen by the caller.
   */
  @Transactional
  public Optional<Message> addAutoAck(
      UUID conversationId, Long businessId, Long handoffId, Map<String, Object> details) {
    Optional<Conversation> conversation = conversations.findForUpdate(conversationId, businessId);
    if (conversation.isEmpty()
        || conversation.get().getState() != WAITING
        || messages.existsAutoAck(conversationId, businessId, handoffId)) {
      return Optional.empty();
    }
    Map<String, Object> metadata = new HashMap<>(details);
    metadata.put("event", SystemEvent.AUTO_ACK.name());
    metadata.put("handoffId", handoffId);
    return Optional.of(
        save(Message.system(conversationId, businessId, SystemEvent.AUTO_ACK, metadata), 0));
  }

  /** Moves the conversation's handoffs along with the state that was just recorded. */
  private void syncHandoffs(
      UUID conversationId, Long businessId, Set<HandoffStatus> from, HandoffStatus to) {
    handoffs.changeStatusOfConversation(conversationId, businessId, from, to, Conversation.now());
  }

  /**
   * Runs after a conditional update. No row changed: throws (not found, or conflict with the
   * current state). Otherwise writes the SYSTEM message and returns the conversation as it is now.
   */
  private Conversation recordTransition(
      int updated,
      UUID conversationId,
      Long businessId,
      SystemEvent event,
      Map<String, Object> details) {
    if (updated == 0) {
      Conversation current = find(conversationId, businessId);
      throw new ConversationConflictException(current);
    }
    Map<String, Object> metadata = new HashMap<>(details);
    metadata.put("event", event.name());
    save(Message.system(conversationId, businessId, event, metadata), 0);
    Conversation conversation = find(conversationId, businessId);
    events.stateChanged(conversation);
    return conversation;
  }

  /**
   * Stores a message, moves the conversation's last_message_at (and unread count) and sends
   * message.created. Every message goes through here, so every one sends its event.
   */
  private Message save(Message message, int unread) {
    Message saved = messages.save(message);
    conversations.recordMessage(
        saved.getConversationId(), saved.getBusinessId(), saved.getCreatedAt(), unread);
    events.messageCreated(saved);
    return saved;
  }

  private Conversation find(UUID conversationId, Long businessId) {
    return conversations
        .findByIdAndBusinessId(conversationId, businessId)
        .orElseThrow(ConversationService::notFound);
  }

  private Conversation findForUpdate(UUID conversationId, Long businessId) {
    return conversations
        .findForUpdate(conversationId, businessId)
        .orElseThrow(ConversationService::notFound);
  }

  private static NotFoundException notFound() {
    return new NotFoundException(
        "Conversation not found", "No conversation with this id in this business.");
  }
}
