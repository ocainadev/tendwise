package dev.ocaina.tendwise.conversation;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A chat between a customer and a business. Only inserted through JPA: every later change goes
 * through a conditional update in {@link ConversationRepository}, never through setters.
 *
 * <p>customer_contact and contact_consent_at are not mapped yet (T41 adds them).
 */
@Entity
@Table(name = "conversations")
public class Conversation {

  /** Created here, not by the database, so the id can be returned before the insert. */
  @Id private UUID id;

  private Long businessId;

  @Enumerated(EnumType.STRING)
  private Channel channel;

  @Enumerated(EnumType.STRING)
  private ConversationState state;

  /** core.users id of the staff member who took over. */
  private Long assigneeUserId;

  /** en, es or pt. Null until it is known. */
  private String language;

  private String customerRef;

  private String tokenHash;

  private Instant lastMessageAt;

  private int unreadByStaff;

  private Instant createdAt;

  private Instant resolvedAt;

  /** Null before the insert, which tells Spring Data the entity is new. */
  @Version private Long version;

  /** Required by JPA. */
  protected Conversation() {}

  /** A new conversation always starts in state AI. */
  public Conversation(
      Long businessId, Channel channel, String language, String customerRef, String tokenHash) {
    this.id = UUID.randomUUID();
    this.businessId = businessId;
    this.channel = channel;
    this.state = ConversationState.AI;
    this.language = language;
    this.customerRef = customerRef;
    this.tokenHash = tokenHash;
    this.createdAt = now();
    this.lastMessageAt = this.createdAt;
  }

  /** PostgreSQL stores microseconds, so values read back are equal to the ones written. */
  static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  public UUID getId() {
    return id;
  }

  public Long getBusinessId() {
    return businessId;
  }

  public Channel getChannel() {
    return channel;
  }

  public ConversationState getState() {
    return state;
  }

  public Long getAssigneeUserId() {
    return assigneeUserId;
  }

  public String getLanguage() {
    return language;
  }

  public String getCustomerRef() {
    return customerRef;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public Instant getLastMessageAt() {
    return lastMessageAt;
  }

  public int getUnreadByStaff() {
    return unreadByStaff;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getResolvedAt() {
    return resolvedAt;
  }

  public Long getVersion() {
    return version;
  }
}
