package dev.ocaina.tendwise.team;

import dev.ocaina.tendwise.user.MembershipRole;
import dev.ocaina.tendwise.user.User;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * An invitation to join a business with a role (T34). Only the SHA-256 hash of the token is stored;
 * the token itself is only in the email. Valid until {@link #getExpiresAt()}, and only once.
 */
@Entity
@Table(name = "invitations")
public class Invitation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long businessId;

  /** Always lowercase. */
  private String email;

  @Enumerated(EnumType.STRING)
  private MembershipRole role;

  /** Hex SHA-256 of the token in the email link. */
  private String tokenHash;

  /** The user (an OWNER) who sent it. */
  private Long invitedBy;

  private Instant expiresAt;

  /** Null while pending. */
  private Instant acceptedAt;

  /** Required by JPA. */
  protected Invitation() {}

  public Invitation(
      Long businessId,
      String email,
      MembershipRole role,
      String tokenHash,
      Long invitedBy,
      Instant expiresAt) {
    this.businessId = businessId;
    this.email = User.normalizeEmail(email);
    this.role = role;
    this.tokenHash = tokenHash;
    this.invitedBy = invitedBy;
    this.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
  }

  /** Not accepted yet and not expired. */
  public boolean isPending(Instant now) {
    return acceptedAt == null && expiresAt.isAfter(now);
  }

  public void markAccepted(Instant now) {
    acceptedAt = now.truncatedTo(ChronoUnit.MICROS);
  }

  public Long getId() {
    return id;
  }

  public Long getBusinessId() {
    return businessId;
  }

  public String getEmail() {
    return email;
  }

  public MembershipRole getRole() {
    return role;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public Long getInvitedBy() {
    return invitedBy;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getAcceptedAt() {
    return acceptedAt;
  }
}
