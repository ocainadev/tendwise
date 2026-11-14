package dev.ocaina.tendwise.conversation;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One message of a conversation. Messages are never changed after they are stored. */
@Entity
@Table(name = "messages")
public class Message {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private UUID conversationId;

  private Long businessId;

  @Enumerated(EnumType.STRING)
  private MessageSender sender;

  /** core.users id, only for HUMAN messages. */
  private Long authorUserId;

  private String body;

  /** true = staff only, never sent to the customer. */
  private boolean internal;

  /** Stored as jsonb, e.g. {"event": "TAKEN_OVER", "userId": 7}. */
  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> metadata;

  private Instant createdAt;

  /** Required by JPA. */
  protected Message() {}

  private Message(
      UUID conversationId,
      Long businessId,
      MessageSender sender,
      String body,
      boolean internal,
      Map<String, Object> metadata) {
    this.conversationId = conversationId;
    this.businessId = businessId;
    this.sender = sender;
    this.body = body;
    this.internal = internal;
    this.metadata = metadata;
    this.createdAt = Conversation.now();
  }

  public static Message customer(UUID conversationId, Long businessId, String body) {
    return new Message(conversationId, businessId, MessageSender.CUSTOMER, body, false, Map.of());
  }

  /** metadata: intent, language, sources and orderCode from agent-service. */
  public static Message ai(
      UUID conversationId, Long businessId, String body, Map<String, Object> metadata) {
    return new Message(conversationId, businessId, MessageSender.AI, body, false, metadata);
  }

  /** Records a state change. The event decides the text and who can see it. */
  public static Message system(
      UUID conversationId, Long businessId, SystemEvent event, Map<String, Object> metadata) {
    return new Message(
        conversationId, businessId, MessageSender.SYSTEM, event.text(), event.internal(), metadata);
  }

  public Long getId() {
    return id;
  }

  public UUID getConversationId() {
    return conversationId;
  }

  public Long getBusinessId() {
    return businessId;
  }

  public MessageSender getSender() {
    return sender;
  }

  public Long getAuthorUserId() {
    return authorUserId;
  }

  public String getBody() {
    return body;
  }

  public boolean isInternal() {
    return internal;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
