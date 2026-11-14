package dev.ocaina.tendwise.user;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** A user's role in one business. A user has at most one membership per business. */
@Entity
@Table(name = "memberships")
public class Membership {

  @EmbeddedId private MembershipId id;

  @Enumerated(EnumType.STRING)
  private MembershipRole role;

  private Instant createdAt;

  /** Required by JPA. */
  protected Membership() {}

  public Membership(Long userId, Long businessId, MembershipRole role) {
    this.id = new MembershipId(userId, businessId);
    this.role = role;
    this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  public MembershipId getId() {
    return id;
  }

  public Long getUserId() {
    return id.userId();
  }

  public Long getBusinessId() {
    return id.businessId();
  }

  public MembershipRole getRole() {
    return role;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
