package dev.ocaina.tendwise.inbox;

import dev.ocaina.tendwise.conversation.Conversation;
import dev.ocaina.tendwise.conversation.ConversationRepository;
import dev.ocaina.tendwise.conversation.ConversationState;
import dev.ocaina.tendwise.conversation.Message;
import dev.ocaina.tendwise.conversation.MessageRepository;
import dev.ocaina.tendwise.conversation.MessageSender;
import dev.ocaina.tendwise.conversation.SystemEvent;
import dev.ocaina.tendwise.handoff.HandoffRepository;
import dev.ocaina.tendwise.inbox.ConversationDetailResponse.HandoffItem;
import dev.ocaina.tendwise.inbox.ConversationDetailResponse.MessageItem;
import dev.ocaina.tendwise.inbox.ConversationListResponse.Item;
import dev.ocaina.tendwise.shared.NotFoundException;
import dev.ocaina.tendwise.user.User;
import dev.ocaina.tendwise.user.UserRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The read side of the inbox (T43). The actions (take over, reply...) are T44. */
@Service
class InboxService {

  static final int DEFAULT_PAGE_SIZE = 30;
  static final int MAX_PAGE_SIZE = 100;
  static final int MAX_QUERY_LENGTH = 200;

  private final InboxQueries inboxQueries;
  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final HandoffRepository handoffs;
  private final UserRepository users;

  InboxService(
      InboxQueries inboxQueries,
      ConversationRepository conversations,
      MessageRepository messages,
      HandoffRepository handoffs,
      UserRepository users) {
    this.inboxQueries = inboxQueries;
    this.conversations = conversations;
    this.messages = messages;
    this.handoffs = handoffs;
    this.users = users;
  }

  /**
   * One page of the list and the tab counts.
   *
   * @param userId the user asking: "me" and the "mine" count mean this user
   * @param assignee "me", a user id, or null for anyone
   * @param query text to search, or null
   * @param cursor nextCursor of the previous page, or null for the first page
   * @param pageSize 1 to {@value #MAX_PAGE_SIZE}
   */
  @Transactional(readOnly = true)
  public ConversationListResponse list(
      long businessId,
      long userId,
      ConversationState state,
      String assignee,
      String query,
      String cursor,
      int pageSize) {
    if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      throw new InvalidInboxRequestException("limit must be between 1 and " + MAX_PAGE_SIZE + ".");
    }
    InboxFilter filter =
        new InboxFilter(
            state, assigneeUserId(assignee, userId), searchText(query), parseCursor(cursor));

    // One row more than the page: if it comes back, there is a next page.
    List<Item> rows = inboxQueries.list(businessId, filter, pageSize + 1);
    List<Item> page = rows.size() > pageSize ? rows.subList(0, pageSize) : rows;
    String nextCursor = null;
    if (rows.size() > pageSize) {
      Item last = page.get(page.size() - 1);
      nextCursor = new InboxCursor(last.lastMessageAt(), last.id()).encode();
    }
    return new ConversationListResponse(
        List.copyOf(page), nextCursor, inboxQueries.counts(businessId, userId));
  }

  /** The conversation with every message and handoff. 404 when it is not in this business. */
  @Transactional(readOnly = true)
  public ConversationDetailResponse detail(long businessId, UUID conversationId) {
    Conversation conversation =
        conversations
            .findByIdAndBusinessId(conversationId, businessId)
            .orElseThrow(
                () -> new NotFoundException("Conversation not found", "No such conversation."));
    List<Message> history =
        messages.findByConversationIdAndBusinessIdOrderByIdAsc(conversationId, businessId);
    Map<Long, String> names = userNames(conversation, history);

    List<MessageItem> messageItems =
        history.stream()
            .map(m ->
                new MessageItem(
                    m.getId(),
                    m.getSender(),
                    m.getAuthorUserId(),
                    m.getAuthorUserId() == null ? null : names.get(m.getAuthorUserId()),
                    m.getBody(),
                    m.isInternal(),
                    m.getMetadata(),
                    m.getCreatedAt()))
            .toList();
    List<HandoffItem> handoffItems =
        handoffs
            .findByBusinessIdAndConversationIdOrderByCreatedAtAsc(businessId, conversationId)
            .stream()
            .map(h ->
                new HandoffItem(
                    h.getId(),
                    h.getReason(),
                    h.getSummary(),
                    h.getStatus(),
                    h.getCreatedAt(),
                    h.getUpdatedAt()))
            .toList();

    Long assigneeId = conversation.getAssigneeUserId();
    return new ConversationDetailResponse(
        conversation.getId(),
        conversation.getState(),
        conversation.getChannel(),
        conversation.getLanguage(),
        assigneeId == null ? null : new Assignee(assigneeId, names.get(assigneeId)),
        conversation.getUnreadByStaff(),
        conversation.getCreatedAt(),
        conversation.getLastMessageAt(),
        conversation.getResolvedAt(),
        conversation.getState() == ConversationState.WAITING ? waitingSince(history) : null,
        messageItems,
        handoffItems);
  }

  /** "me" is the user asking; a number is that user. */
  private static Long assigneeUserId(String assignee, long userId) {
    if (assignee == null || assignee.isBlank()) {
      return null;
    }
    if (assignee.equals("me")) {
      return userId;
    }
    try {
      return Long.parseLong(assignee);
    } catch (NumberFormatException e) {
      throw new InvalidInboxRequestException("assignee must be \"me\" or a user id.");
    }
  }

  private static String searchText(String query) {
    if (query == null || query.isBlank()) {
      return null;
    }
    String trimmed = query.trim();
    if (trimmed.length() > MAX_QUERY_LENGTH) {
      throw new InvalidInboxRequestException(
          "q must be at most " + MAX_QUERY_LENGTH + " characters.");
    }
    return trimmed;
  }

  private static InboxCursor parseCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      return InboxCursor.decode(cursor);
    } catch (IllegalArgumentException e) {
      throw new InvalidInboxRequestException("cursor is not one returned by this API.");
    }
  }

  /** Names of the assignee and of the staff who wrote messages, read in one query. */
  private Map<Long, String> userNames(Conversation conversation, List<Message> history) {
    Set<Long> ids = new HashSet<>();
    if (conversation.getAssigneeUserId() != null) {
      ids.add(conversation.getAssigneeUserId());
    }
    for (Message message : history) {
      if (message.getAuthorUserId() != null) {
        ids.add(message.getAuthorUserId());
      }
    }
    Map<Long, String> names = new HashMap<>();
    if (!ids.isEmpty()) {
      for (User user : users.findAllById(ids)) {
        names.put(user.getId(), user.getName());
      }
    }
    return names;
  }

  /**
   * Time of the newest SYSTEM message that moved the conversation to WAITING (same as the list).
   */
  private static Instant waitingSince(List<Message> history) {
    Instant since = null;
    for (Message message : history) {
      if (message.getSender() == MessageSender.SYSTEM && movedToWaiting(message)) {
        since = message.getCreatedAt();
      }
    }
    return since;
  }

  private static boolean movedToWaiting(Message message) {
    Object event = message.getMetadata() == null ? null : message.getMetadata().get("event");
    return SystemEvent.HANDOFF_CREATED.name().equals(event)
        || SystemEvent.AI_FAILED.name().equals(event);
  }
}
