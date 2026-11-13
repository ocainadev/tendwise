package dev.ocaina.tendwise.handoff;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** A conversation the agent passed to a human. Created and updated through the API (T05). */
@Entity
@Table(name = "handoffs")
public class Handoff {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long businessId;

  /** Id of the conversation in agent-service. */
  private UUID conversationId;

  @Enumerated(EnumType.STRING)
  private HandoffReason reason;

  /** Short LLM-written summary of the conversation for the staff member. */
  private String summary;

  @Enumerated(EnumType.STRING)
  private HandoffStatus status;

  private Instant createdAt;

  private Instant updatedAt;

  /** Required by JPA. */
  protected Handoff() {}

  /** A new handoff always starts OPEN. */
  public Handoff(Long businessId, UUID conversationId, HandoffReason reason, String summary) {
    this.businessId = businessId;
    this.conversationId = conversationId;
    this.reason = reason;
    this.summary = summary;
    this.status = HandoffStatus.OPEN;
    this.createdAt = now();
    this.updatedAt = this.createdAt;
  }

  /** Any change is allowed (no state machine in the MVP). Also refreshes updatedAt. */
  public void changeStatus(HandoffStatus newStatus) {
    this.status = newStatus;
    this.updatedAt = now();
  }

  /**
   * PostgreSQL stores microseconds. Truncating here means the value in the create response is the
   * same one a later read returns from the database.
   */
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  public Long getId() {
    return id;
  }

  public Long getBusinessId() {
    return businessId;
  }

  public UUID getConversationId() {
    return conversationId;
  }

  public HandoffReason getReason() {
    return reason;
  }

  public String getSummary() {
    return summary;
  }

  public HandoffStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
