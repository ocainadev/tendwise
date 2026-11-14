package dev.ocaina.tendwise.job;

import jakarta.persistence.Column;
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

/**
 * A row of the core.jobs queue. T36 only inserts jobs; the worker that runs them is T39. The queue
 * is platform-wide, so the business id travels inside the payload.
 */
@Entity
@Table(name = "jobs")
public class Job {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  private JobType type;

  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> payload;

  @Enumerated(EnumType.STRING)
  private JobStatus status;

  private int attempts;

  private Instant runAt;

  private Instant lockedAt;

  /** Error type only, never message text. */
  private String lastError;

  /** Set by the database default. */
  @Column(insertable = false, updatable = false)
  private Instant createdAt;

  /** Required by JPA. */
  protected Job() {}

  private Job(JobType type, Map<String, Object> payload) {
    this.type = type;
    this.payload = payload;
    this.status = JobStatus.PENDING;
    this.runAt = Instant.now();
  }

  /** Answer the customer message messageId of the conversation. Runs as soon as possible. */
  public static Job aiReply(Long businessId, UUID conversationId, Long messageId) {
    return new Job(
        JobType.AI_REPLY,
        Map.of(
            "businessId", businessId,
            "conversationId", conversationId.toString(),
            "messageId", messageId));
  }

  public Long getId() {
    return id;
  }

  public JobType getType() {
    return type;
  }

  public Map<String, Object> getPayload() {
    return payload;
  }

  public JobStatus getStatus() {
    return status;
  }

  public int getAttempts() {
    return attempts;
  }

  public Instant getRunAt() {
    return runAt;
  }

  public Instant getLockedAt() {
    return lockedAt;
  }

  public String getLastError() {
    return lastError;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
