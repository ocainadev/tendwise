package dev.ocaina.tendwise.publicapi;

import dev.ocaina.tendwise.auth.SecureTokens;
import dev.ocaina.tendwise.business.Business;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.conversation.Channel;
import dev.ocaina.tendwise.conversation.Conversation;
import dev.ocaina.tendwise.conversation.ConversationRepository;
import dev.ocaina.tendwise.conversation.ConversationService;
import dev.ocaina.tendwise.conversation.Message;
import dev.ocaina.tendwise.conversation.MessageRepository;
import dev.ocaina.tendwise.conversation.MessageSender;
import dev.ocaina.tendwise.conversation.SystemEvent;
import dev.ocaina.tendwise.realtime.EventStreams;
import dev.ocaina.tendwise.realtime.EventType;
import dev.ocaina.tendwise.realtime.RealtimeEvent;
import dev.ocaina.tendwise.shared.NotFoundException;
import dev.ocaina.tendwise.user.User;
import dev.ocaina.tendwise.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The public conversation API used by the website chat widget (T38).
 *
 * <p>Who is calling: the publishable key says which business a new conversation is for; after that,
 * the conversation token is the only credential. The business always comes from the row found
 * (business or conversation), never from the request.
 *
 * <p>Checks, in order: the IP rate limit (POSTs only), the key or token (404), the origin (403),
 * then, for a message, the per-conversation rate limit (429).
 */
@Service
public class PublicConversationService {

  /** At most this many messages per history call; the widget asks again with the last id. */
  static final int HISTORY_PAGE_SIZE = 100;

  private static final String BEARER = "Bearer ";

  private final BusinessRepository businesses;
  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final UserRepository users;
  private final ConversationService conversationService;
  private final IpRateLimiter ipRateLimiter;
  private final EventStreams eventStreams;
  private final int conversationLimit;
  private final Duration conversationWindow;

  public PublicConversationService(
      BusinessRepository businesses,
      ConversationRepository conversations,
      MessageRepository messages,
      UserRepository users,
      ConversationService conversationService,
      IpRateLimiter ipRateLimiter,
      EventStreams eventStreams,
      @Value("${tendwise.public-api.conversation-limit}") int conversationLimit,
      @Value("${tendwise.public-api.conversation-window}") Duration conversationWindow) {
    this.businesses = businesses;
    this.conversations = conversations;
    this.messages = messages;
    this.users = users;
    this.conversationService = conversationService;
    this.ipRateLimiter = ipRateLimiter;
    this.eventStreams = eventStreams;
    this.conversationLimit = conversationLimit;
    this.conversationWindow = conversationWindow;
  }

  /** Starts a WEB conversation in state AI and returns its token (the only time it is shown). */
  @Transactional
  public StartConversationResponse start(
      String publishableKey, String language, String origin, String ip) {
    checkIpLimit(ip);
    Business business =
        businesses
            .findByPublishableKey(publishableKey)
            .orElseThrow(() ->
                new NotFoundException(
                    "Business not found", "No business with this publishable key."));
    checkOrigin(business, origin);

    String token = SecureTokens.generate();
    String customerRef = UUID.randomUUID().toString();
    Conversation conversation =
        conversationService.start(
            business.getId(), Channel.WEB, language, customerRef, SecureTokens.sha256Hex(token));

    String greetingLanguage = language != null ? language : business.getDefaultLanguage();
    return new StartConversationResponse(
        conversation.getId(), token, Greetings.forBusiness(greetingLanguage, business.getName()));
  }

  /**
   * Stores a customer message through the state machine: in state AI it also queues the AI_REPLY
   * job, in the same transaction.
   */
  @Transactional
  public PublicMessage send(
      UUID conversationId, String authorization, String body, String origin, String ip) {
    checkIpLimit(ip);
    Conversation conversation = findByToken(conversationId, authorization);
    Long businessId = conversation.getBusinessId();
    checkOrigin(findBusiness(businessId), origin);

    // Lock the conversation row first, then count: two messages sent at the same time (even to two
    // instances) wait for each other, so both cannot slip under the limit.
    conversations.findForUpdate(conversationId, businessId);
    Instant since = Instant.now().minus(conversationWindow);
    if (messages.countCustomerMessagesSince(conversationId, businessId, since)
        >= conversationLimit) {
      throw new RateLimitExceededException(
          "Too many messages in this conversation. Please wait a few minutes.");
    }

    Message message = conversationService.addCustomerMessage(conversationId, businessId, body);
    return PublicMessage.from(message, null);
  }

  /** Messages after afterId that the customer may see (never internal ones), oldest first. */
  @Transactional(readOnly = true)
  public List<PublicMessage> history(
      UUID conversationId, String authorization, long afterId, String origin) {
    Conversation conversation = findByToken(conversationId, authorization);
    Long businessId = conversation.getBusinessId();
    checkOrigin(findBusiness(businessId), origin);

    List<Message> found =
        messages.findVisibleAfter(conversationId, businessId, afterId, Limit.of(HISTORY_PAGE_SIZE));
    Map<Long, String> firstNames = staffFirstNames(found);
    return found.stream()
        .map(message -> PublicMessage.from(message, authorName(message, firstNames)))
        .toList();
  }

  /**
   * Opens the conversation's live stream (T40), with the same checks as the history: token (404),
   * then origin (403). lastEventId: the newest message id the widget has, sent by the browser on a
   * reconnect. The stream then first replays message.created for each newer message the customer
   * may see (at most one history page; the widget fetches them with the history call) and the
   * current state.
   */
  @Transactional(readOnly = true)
  public SseEmitter openEventStream(
      UUID conversationId, String authorization, Long lastEventId, String origin) {
    Conversation conversation = findByToken(conversationId, authorization);
    Long businessId = conversation.getBusinessId();
    checkOrigin(findBusiness(businessId), origin);

    List<RealtimeEvent> missed = new ArrayList<>();
    if (lastEventId != null) {
      for (Message message :
          messages.findVisibleAfter(
              conversationId, businessId, lastEventId, Limit.of(HISTORY_PAGE_SIZE))) {
        missed.add(
            new RealtimeEvent(
                businessId,
                conversationId,
                EventType.MESSAGE_CREATED,
                message.getId(),
                false,
                null));
      }
      missed.add(
          new RealtimeEvent(
              businessId,
              conversationId,
              EventType.CONVERSATION_STATE_CHANGED,
              null,
              false,
              conversation.getState().name()));
    }
    return eventStreams.openWidgetStream(businessId, conversationId, missed);
  }

  /**
   * The staff member a message names: the author of a HUMAN message, or who joined for a SYSTEM
   * TAKEN_OVER message. Null for every other message (Map.get(null) would throw on an immutable
   * map).
   */
  private static String authorName(Message message, Map<Long, String> firstNames) {
    Long staffId = staffUserId(message);
    return staffId == null ? null : firstNames.get(staffId);
  }

  private static Long staffUserId(Message message) {
    if (message.getSender() == MessageSender.HUMAN) {
      return message.getAuthorUserId();
    }
    if (message.getSender() == MessageSender.SYSTEM
        && message.getMetadata() != null
        && SystemEvent.TAKEN_OVER.name().equals(message.getMetadata().get("event"))
        && message.getMetadata().get("userId") instanceof Number userId) {
      // jsonb numbers come back as Integer.
      return userId.longValue();
    }
    return null;
  }

  private void checkIpLimit(String ip) {
    if (!ipRateLimiter.tryAcquire(ip)) {
      throw new RateLimitExceededException(
          "Too many requests from this network. Please wait a minute.");
    }
  }

  /** A missing header, a wrong token and an unknown id all give the same 404. */
  private Conversation findByToken(UUID conversationId, String authorization) {
    if (authorization == null || !authorization.startsWith(BEARER)) {
      throw conversationNotFound();
    }
    String token = authorization.substring(BEARER.length()).trim();
    if (token.isEmpty()) {
      throw conversationNotFound();
    }
    return conversations
        .findByIdAndTokenHash(conversationId, SecureTokens.sha256Hex(token))
        .orElseThrow(PublicConversationService::conversationNotFound);
  }

  private Business findBusiness(Long businessId) {
    return businesses
        .findById(businessId)
        .orElseThrow(PublicConversationService::conversationNotFound);
  }

  /**
   * A browser always sends Origin on a cross-site call, so a foreign website is rejected. A request
   * without Origin is not from another website (same-origin GET, curl): it is let through, since a
   * non-browser could send any Origin anyway. The token and the rate limits still apply.
   */
  private static void checkOrigin(Business business, String origin) {
    if (origin == null) {
      return;
    }
    boolean allowed =
        business.getWidgetSettings().allowedDomains().stream()
            .anyMatch(domain -> sameOrigin(domain, origin));
    if (!allowed) {
      throw new OriginNotAllowedException();
    }
  }

  /** "https://Shop.example.com/" and "https://shop.example.com" are the same origin. */
  private static boolean sameOrigin(String allowedDomain, String origin) {
    return stripTrailingSlash(allowedDomain).equalsIgnoreCase(stripTrailingSlash(origin));
  }

  private static String stripTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  /** First names of the staff the messages name (see authorName), by user id. */
  private Map<Long, String> staffFirstNames(List<Message> found) {
    Set<Long> authorIds =
        found.stream()
            .map(PublicConversationService::staffUserId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (authorIds.isEmpty()) {
      return Map.of();
    }
    return users.findAllById(authorIds).stream()
        .collect(Collectors.toMap(User::getId, user -> firstName(user.getName()), (a, b) -> a));
  }

  private static String firstName(String fullName) {
    return fullName.strip().split("\\s+")[0];
  }

  private static NotFoundException conversationNotFound() {
    return new NotFoundException(
        "Conversation not found", "No conversation with this id and token.");
  }
}
