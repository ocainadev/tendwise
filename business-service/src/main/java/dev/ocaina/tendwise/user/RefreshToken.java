package dev.ocaina.tendwise.user;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One refresh token of a login session (ADR 0003). Only its SHA-256 hash is stored. Tokens of the
 * same login share a familyId, so reusing an old one can revoke them all. Issued from T31.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long userId;

  /** Hex SHA-256 of the token the browser holds. */
  private String tokenHash;

  private UUID familyId;

  private Instant expiresAt;

  /** Null while the token is still valid. */
  private Instant revokedAt;

  private Instant createdAt;

  /** Required by JPA. */
  protected RefreshToken() {}

  public RefreshToken(Long userId, String tokenHash, UUID familyId, Instant expiresAt) {
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.familyId = familyId;
    this.expiresAt = expiresAt;
    this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public UUID getFamilyId() {
    return familyId;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
