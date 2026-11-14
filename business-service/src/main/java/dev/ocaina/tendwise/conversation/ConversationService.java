package dev.ocaina.tendwise.conversation;

import static dev.ocaina.tendwise.conversation.ConversationState.AI;
import static dev.ocaina.tendwise.conversation.ConversationState.HUMAN;
import static dev.ocaina.tendwise.conversation.ConversationState.RESOLVED;
import static dev.ocaina.tendwise.conversation.ConversationState.WAITING;

import dev.ocaina.tendwise.handoff.HandoffReason;
import dev.ocaina.tendwise.job.Job;
import dev.ocaina.tendwise.job.JobRepository;
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
 * <p>An unknown id and an id of another business both throw the same {@link NotFoundException}.
 */
@Service
public class ConversationService {

  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final JobRepository jobs;

  public ConversationService(
      ConversationRepository conversations, MessageRepository messages, JobRepository jobs) {
    this.conversations = conversations;
    this.messages = messages;
    this.jobs = jobs;
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
    if (state == AI) {
      jobs.save(Job.aiReply(businessId, conversationId, message.getId()));
    }
    return message;
  }

  /**
   * Stores the AI's reply, but only while the conversation is still in AI. Empty means the reply
   * was dropped because staff took over meanwhile: the AI never talks over a person.
   */
  @Transactional
  public Optional<Message> addAiMessage(
      UUID conversationId, Long businessId, String body, Map<String, Object> metadata) {
    Conversation conversation = findForUpdate(conversationId, businessId);
    if (conversation.getState() != AI) {
      return Optional.empty();
    }
    return Optional.of(save(Message.ai(conversationId, businessId, body, metadata), 0));
  }

  /** AI → WAITING: a handoff was created. The AI stops answering until staff act. */
  @Transactional
  public Conversation handOff(UUID conversationId, Long businessId, HandoffReason reason) {
    int updated = conversations.changeState(conversationId, businessId, Set.of(AI), WAITING, null);
    return recordTransition(
        updated,
        conversationId,
        businessId,
        SystemEvent.HANDOFF_CREATED,
        Map.of("reason", reason.name()));
  }

  /** WAITING → HUMAN: the staff member userId takes over. Two at once: only one wins. */
  @Transactional
  public Conversation takeOver(UUID conversationId, Long businessId, Long userId) {
    int updated =
        conversations.changeState(conversationId, businessId, Set.of(WAITING), HUMAN, userId);
    return recordTransition(
        updated, conversationId, businessId, SystemEvent.TAKEN_OVER, Map.of("userId", userId));
  }

  /** WAITING or HUMAN → AI: staff hand the conversation back to the AI. */
  @Transactional
  public Conversation release(UUID conversationId, Long businessId, Long userId) {
    int updated =
        conversations.changeState(conversationId, businessId, Set.of(WAITING, HUMAN), AI, null);
    return recordTransition(
        updated, conversationId, businessId, SystemEvent.RELEASED, Map.of("userId", userId));
  }

  /** AI, WAITING or HUMAN → RESOLVED: staff close the conversation. */
  @Transactional
  public Conversation resolve(UUID conversationId, Long businessId, Long userId) {
    int updated =
        conversations.resolve(
            conversationId, businessId, Set.of(AI, WAITING, HUMAN), Conversation.now());
    return recordTransition(
        updated, conversationId, businessId, SystemEvent.RESOLVED, Map.of("userId", userId));
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
    return find(conversationId, businessId);
  }

  /** Stores a message and moves the conversation's last_message_at (and unread count). */
  private Message save(Message message, int unread) {
    Message saved = messages.save(message);
    conversations.recordMessage(
        saved.getConversationId(), saved.getBusinessId(), saved.getCreatedAt(), unread);
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
