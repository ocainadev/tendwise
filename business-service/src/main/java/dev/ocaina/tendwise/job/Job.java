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
 * A row of the core.jobs queue. Other code only inserts jobs; {@link JobWorker} runs them and is
 * the only one that changes their status (through {@link JobQueue}). The queue is platform-wide, so
 * the business id travels inside the payload.
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

  private Job(JobType type, Map<String, Object> payload, Instant runAt) {
    this.type = type;
    this.payload = payload;
    this.status = JobStatus.PENDING;
    this.runAt = runAt;
  }

  /** Answer the customer message messageId of the conversation. Runs as soon as possible. */
  public static Job aiReply(Long businessId, UUID conversationId, Long messageId) {
    return new Job(
        JobType.AI_REPLY,
        Map.of(
            "businessId", businessId,
            "conversationId", conversationId.toString(),
            "messageId", messageId),
        Instant.now());
  }

  /**
   * Send the one automatic message of handoff handoffId, at runAt, if nobody took over by then
   * (handler: AutoAckJobHandler, run by the worker in T39).
   */
  public static Job autoAck(Long businessId, UUID conversationId, Long handoffId, Instant runAt) {
    return new Job(
        JobType.AUTO_ACK,
        Map.of(
            "businessId", businessId,
            "conversationId", conversationId.toString(),
            "handoffId", handoffId),
        runAt);
  }

  /** Taken by a worker: one more attempt starts now. */
  void markRunning(Instant now) {
    this.status = JobStatus.RUNNING;
    this.attempts = this.attempts + 1;
    this.lockedAt = now;
  }

  void markDone() {
    this.status = JobStatus.DONE;
    this.lockedAt = null;
  }

  /** The attempt failed; the job runs again at runAt. error: the error type, never message text. */
  void retryAt(Instant runAt, String error) {
    this.status = JobStatus.PENDING;
    this.runAt = runAt;
    this.lockedAt = null;
    this.lastError = error;
  }

  /** The last attempt failed; the job is never run again. */
  void markFailed(String error) {
    this.status = JobStatus.FAILED;
    this.lockedAt = null;
    this.lastError = error;
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
