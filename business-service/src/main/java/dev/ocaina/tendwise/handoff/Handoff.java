package dev.ocaina.tendwise.handoff;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
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

  /** Why the agent handed off, e.g. complaint, human_request, low_confidence. */
  private String reason;

  /** Short LLM-written summary of the conversation for the staff member. */
  private String summary;

  @Enumerated(EnumType.STRING)
  private HandoffStatus status;

  /** Set by the database default. */
  @Column(insertable = false, updatable = false)
  private Instant createdAt;

  private Instant updatedAt;

  /** Required by JPA. */
  protected Handoff() {}

  public Long getId() {
    return id;
  }

  public Long getBusinessId() {
    return businessId;
  }

  public UUID getConversationId() {
    return conversationId;
  }

  public String getReason() {
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
