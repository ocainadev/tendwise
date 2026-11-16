package dev.ocaina.tendwise.aireply;

import dev.ocaina.tendwise.business.Business;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.conversation.ConversationService;
import dev.ocaina.tendwise.conversation.Message;
import dev.ocaina.tendwise.conversation.MessageRepository;
import dev.ocaina.tendwise.conversation.MessageSender;
import dev.ocaina.tendwise.job.Job;
import dev.ocaina.tendwise.job.JobHandler;
import dev.ocaina.tendwise.job.JobType;
import dev.ocaina.tendwise.realtime.EventPublisher;
import dev.ocaina.tendwise.user.User;
import dev.ocaina.tendwise.user.UserRepository;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs an AI_REPLY job (T39, docs/architecture.md "Message flow"): asks agent-service to answer one
 * customer message and stores the AI message.
 *
 * <ol>
 *   <li>Skip when the conversation is no longer in AI (a handoff or staff got there first).
 *   <li>Send the customer message and what staff told the customer since the last AI message.
 *   <li>Store the reply through {@link ConversationService}, with intent, language, sources and
 *       orderCode in metadata. If the agent created a handoff during the call, the conversation is
 *       already WAITING: the reply is stored anyway and the state is not touched.
 * </ol>
 *
 * <p>Live events (T40): ai.typing is sent right before the agent call. The stored AI message (or
 * the AI_FAILED message and state change) sends its own events when its transaction commits, from
 * {@link ConversationService}.
 *
 * <p>This method runs outside any transaction: the agent call takes seconds and must not hold a
 * database connection. An exception is a failed attempt; after the last one {@link #onFinalFailure}
 * moves the conversation to WAITING with an AI_FAILED message.
 */
@Component
public class AiReplyJobHandler implements JobHandler {

  private static final Logger log = LoggerFactory.getLogger(AiReplyJobHandler.class);

  /** Limits of /internal/v1/replies (T37): at most 50 staff messages of at most 4000 chars. */
  static final int MAX_STAFF_MESSAGES = 50;

  static final int MAX_BODY_LENGTH = 4000;

  private final ConversationService conversations;
  private final MessageRepository messages;
  private final BusinessRepository businesses;
  private final UserRepository users;
  private final AgentServiceClient agentService;
  private final EventPublisher events;

  public AiReplyJobHandler(
      ConversationService conversations,
      MessageRepository messages,
      BusinessRepository businesses,
      UserRepository users,
      AgentServiceClient agentService,
      EventPublisher events) {
    this.conversations = conversations;
    this.messages = messages;
    this.businesses = businesses;
    this.users = users;
    this.agentService = agentService;
    this.events = events;
  }

  @Override
  public JobType type() {
    return JobType.AI_REPLY;
  }

  @Override
  public void run(Job job) {
    Payload payload = Payload.of(job);
    if (!conversations.isAiAnswering(payload.conversationId(), payload.businessId())) {
      log.info("AI_REPLY job {} skipped: conversation is no longer in AI", job.getId());
      return;
    }
    Optional<Message> customerMessage =
        messages
            .findByIdAndConversationIdAndBusinessId(
                payload.messageId(), payload.conversationId(), payload.businessId())
            .filter(message -> message.getSender() == MessageSender.CUSTOMER);
    Optional<Business> business = businesses.findById(payload.businessId());
    if (customerMessage.isEmpty() || business.isEmpty()) {
      log.warn("AI_REPLY job {} skipped: message or business not found", job.getId());
      return;
    }

    // No transaction here, so the NOTIFY goes out at once (it is never stored).
    events.aiTyping(payload.businessId(), payload.conversationId());
    ReplyResponse response =
        agentService.reply(
            new ReplyRequest(
                payload.conversationId(),
                business.get().getSlug(),
                customerMessage.get().getBody(),
                staffMessagesSinceLastAiTurn(payload.conversationId(), payload.businessId())));

    Optional<Message> stored =
        conversations.addAiMessage(
            payload.conversationId(),
            payload.businessId(),
            response.reply(),
            metadata(response),
            response.handoff());
    if (stored.isEmpty()) {
      log.info("AI_REPLY job {}: reply dropped, staff took the conversation", job.getId());
    }
  }

  /** Last attempt failed: a person picks the conversation up instead of the customer waiting. */
  @Override
  public void onFinalFailure(Job job) {
    Payload payload = Payload.of(job);
    // Sends message.created (AI_FAILED) and conversation.state_changed after its commit.
    conversations.failAiReply(payload.conversationId(), payload.businessId());
  }

  /**
   * HUMAN messages the customer saw since the last AI message, oldest first, with the author's
   * name. agent-service stores them in its history, so each one is sent only once.
   */
  private List<ReplyRequest.StaffMessage> staffMessagesSinceLastAiTurn(
      UUID conversationId, Long businessId) {
    Long lastAiMessageId = messages.findLastAiMessageId(conversationId, businessId);
    List<Message> staffMessages =
        messages.findStaffMessagesAfter(
            conversationId, businessId, lastAiMessageId == null ? 0 : lastAiMessageId);
    if (staffMessages.size() > MAX_STAFF_MESSAGES) {
      staffMessages =
          staffMessages.subList(staffMessages.size() - MAX_STAFF_MESSAGES, staffMessages.size());
    }
    Map<Long, String> names =
        users.findAllById(staffMessages.stream().map(Message::getAuthorUserId).toList()).stream()
            .collect(Collectors.toMap(User::getId, User::getName));
    return staffMessages.stream()
        .map(message ->
            new ReplyRequest.StaffMessage(
                names.getOrDefault(message.getAuthorUserId(), "Staff"),
                truncate(message.getBody())))
        .toList();
  }

  /** messages.metadata of an AI message (docs/architecture.md, "Conversation columns"). */
  static Map<String, Object> metadata(ReplyResponse response) {
    List<Map<String, Object>> sources =
        response.sources() == null
            ? List.of()
            : response.sources().stream().map(AiReplyJobHandler::source).toList();
    // HashMap, not Map.of: intent and orderCode may be null.
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("intent", response.intent());
    metadata.put("language", response.language());
    metadata.put("sources", sources);
    metadata.put("orderCode", response.orderCode());
    return metadata;
  }

  private static Map<String, Object> source(ReplyResponse.Source source) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("title", source.title());
    map.put("source_path", source.sourcePath());
    map.put("section", source.section());
    return map;
  }

  private static String truncate(String body) {
    return body.length() <= MAX_BODY_LENGTH ? body : body.substring(0, MAX_BODY_LENGTH);
  }

  /** What Job.aiReply puts in the payload. jsonb numbers come back as Integer, so read Number. */
  private record Payload(Long businessId, UUID conversationId, Long messageId) {
    static Payload of(Job job) {
      Map<String, Object> payload = job.getPayload();
      return new Payload(
          ((Number) payload.get("businessId")).longValue(),
          UUID.fromString((String) payload.get("conversationId")),
          ((Number) payload.get("messageId")).longValue());
    }
  }
}
