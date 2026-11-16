package dev.ocaina.tendwise.inbox;

import dev.ocaina.tendwise.audit.AuditEntry;
import dev.ocaina.tendwise.audit.AuditLogRepository;
import dev.ocaina.tendwise.conversation.Conversation;
import dev.ocaina.tendwise.conversation.ConversationService;
import dev.ocaina.tendwise.conversation.Message;
import dev.ocaina.tendwise.inbox.ConversationDetailResponse.MessageItem;
import dev.ocaina.tendwise.realtime.EventPublisher;
import dev.ocaina.tendwise.shared.BadRequestException;
import dev.ocaina.tendwise.user.MembershipId;
import dev.ocaina.tendwise.user.MembershipRepository;
import dev.ocaina.tendwise.user.User;
import dev.ocaina.tendwise.user.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The inbox actions (docs/architecture.md, "Panel API", T44). Every state change goes through
 * {@link ConversationService} (conditional updates, SYSTEM message, handoff sync and live events)
 * and writes an audit_log entry in the same transaction. The state-changing actions answer with the
 * conversation as {@code GET …/conversations/{id}} shows it, so the panel can redraw at once.
 */
@Service
class InboxActionService {

  private static final String CONVERSATION = "conversation";

  private final ConversationService conversations;
  private final InboxService inbox;
  private final AuditLogRepository auditLog;
  private final MembershipRepository memberships;
  private final UserRepository users;
  private final EventPublisher events;
  private final StaffTypingLimiter typingLimiter;

  InboxActionService(
      ConversationService conversations,
      InboxService inbox,
      AuditLogRepository auditLog,
      MembershipRepository memberships,
      UserRepository users,
      EventPublisher events,
      StaffTypingLimiter typingLimiter) {
    this.conversations = conversations;
    this.inbox = inbox;
    this.auditLog = auditLog;
    this.memberships = memberships;
    this.users = users;
    this.events = events;
    this.typingLimiter = typingLimiter;
  }

  @Transactional
  public ConversationDetailResponse takeOver(long businessId, UUID conversationId, long userId) {
    conversations.takeOver(conversationId, businessId, userId);
    audit(userId, businessId, "conversation.takeover", conversationId, Map.of());
    return inbox.detail(businessId, conversationId);
  }

  /** 400 when assigneeUserId is not a member of this business. */
  @Transactional
  public ConversationDetailResponse assign(
      long businessId, UUID conversationId, long userId, long assigneeUserId) {
    if (!memberships.existsById(new MembershipId(assigneeUserId, businessId))) {
      throw new BadRequestException("Invalid request", "userId is not a member of this business.");
    }
    conversations.assign(conversationId, businessId, assigneeUserId, userId);
    audit(
        userId,
        businessId,
        "conversation.assign",
        conversationId,
        Map.of("assigneeUserId", assigneeUserId));
    return inbox.detail(businessId, conversationId);
  }

  /**
   * Back to the AI. Queues nothing: the next customer message queues the AI reply, which then gets
   * what staff said (T39).
   */
  @Transactional
  public ConversationDetailResponse release(long businessId, UUID conversationId, long userId) {
    conversations.release(conversationId, businessId, userId);
    audit(userId, businessId, "conversation.release", conversationId, Map.of());
    return inbox.detail(businessId, conversationId);
  }

  @Transactional
  public ConversationDetailResponse resolve(long businessId, UUID conversationId, long userId) {
    conversations.resolve(conversationId, businessId, userId);
    audit(userId, businessId, "conversation.resolve", conversationId, Map.of());
    return inbox.detail(businessId, conversationId);
  }

  /**
   * A reply to the customer (only the assignee) or an internal note (any member). Not audited: the
   * message itself is the record.
   */
  @Transactional
  public MessageItem sendMessage(
      long businessId, UUID conversationId, long userId, StaffMessageRequest request) {
    Message message =
        conversations.addStaffMessage(
            conversationId, businessId, userId, request.body(), request.internal());
    String authorName = users.findById(userId).map(User::getName).orElse(null);
    return new MessageItem(
        message.getId(),
        message.getSender(),
        message.getAuthorUserId(),
        authorName,
        message.getBody(),
        message.isInternal(),
        message.getMetadata(),
        message.getCreatedAt());
  }

  @Transactional
  public void markRead(long businessId, UUID conversationId) {
    conversations.markRead(conversationId, businessId);
  }

  /**
   * Sends staff.typing with the assignee's first name, at most once every 3 seconds; calls in
   * between are accepted and dropped. Not in a transaction, so the NOTIFY goes out at once.
   */
  public void typing(long businessId, UUID conversationId, long userId) {
    Conversation conversation = conversations.requireAssignee(conversationId, businessId, userId);
    if (!typingLimiter.tryAcquire(conversation.getId(), userId)) {
      return;
    }
    String firstName = users.findById(userId).map(user -> firstName(user.getName())).orElse(null);
    events.staffTyping(businessId, conversationId, firstName);
  }

  private void audit(
      long userId,
      long businessId,
      String action,
      UUID conversationId,
      Map<String, Object> metadata) {
    auditLog.save(
        new AuditEntry(
            userId, businessId, action, CONVERSATION, conversationId.toString(), metadata));
  }

  private static String firstName(String fullName) {
    return fullName.strip().split("\\s+")[0];
  }
}
