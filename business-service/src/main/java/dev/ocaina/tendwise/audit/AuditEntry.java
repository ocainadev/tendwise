package dev.ocaina.tendwise.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One line of core.audit_log: who did what to which record. Append-only: entries are inserted and
 * never changed. metadata holds ids and names of states, never message text or personal data.
 */
@Entity
@Table(name = "audit_log")
public class AuditEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long actorUserId;

  /** Null for platform-level actions. */
  private Long businessId;

  /** e.g. conversation.takeover. */
  private String action;

  /** e.g. conversation. */
  private String targetType;

  private String targetId;

  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> metadata;

  /** Not filled by T44. */
  private String ipHash;

  /** True for actions taken by the operator in support mode (T64). */
  private boolean supportMode;

  private Instant createdAt;

  /** Required by JPA. */
  protected AuditEntry() {}

  public AuditEntry(
      Long actorUserId,
      Long businessId,
      String action,
      String targetType,
      String targetId,
      Map<String, Object> metadata) {
    this.actorUserId = actorUserId;
    this.businessId = businessId;
    this.action = action;
    this.targetType = targetType;
    this.targetId = targetId;
    this.metadata = metadata;
    this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  public Long getId() {
    return id;
  }

  public Long getActorUserId() {
    return actorUserId;
  }

  public Long getBusinessId() {
    return businessId;
  }

  public String getAction() {
    return action;
  }

  public String getTargetType() {
    return targetType;
  }

  public String getTargetId() {
    return targetId;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  public String getIpHash() {
    return ipHash;
  }

  public boolean isSupportMode() {
    return supportMode;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
