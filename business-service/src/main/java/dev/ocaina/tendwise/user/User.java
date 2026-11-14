package dev.ocaina.tendwise.user;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * A person who logs in to a panel: the platform operator or a business's staff. Which businesses
 * they can open is in {@link Membership}.
 */
@Entity
@Table(name = "users")
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Always lowercase (see {@link #normalizeEmail(String)}). */
  private String email;

  /** Argon2id hash, never the password itself. */
  private String passwordHash;

  private String name;

  /** ADMIN for the operator, null for business staff. */
  @Enumerated(EnumType.STRING)
  private PlatformRole platformRole;

  /** Encrypted TOTP secret. Stays null until T68. */
  private String totpSecret;

  @Enumerated(EnumType.STRING)
  private UserStatus status;

  /** Consecutive failed logins since the last successful login or the last lock. */
  private int failedLoginCount;

  private Instant lockedUntil;

  private Instant createdAt;

  private Instant lastLoginAt;

  /** Required by JPA. */
  protected User() {}

  /** A new user is ACTIVE, with no failed logins. */
  public User(String email, String passwordHash, String name, PlatformRole platformRole) {
    this.email = normalizeEmail(email);
    this.passwordHash = passwordHash;
    this.name = name;
    this.platformRole = platformRole;
    this.status = UserStatus.ACTIVE;
    this.failedLoginCount = 0;
    this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  /** Emails are compared and stored trimmed and lowercase. */
  public static String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  /** True while a lock from too many failed logins is still running. */
  public boolean isLocked(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  /**
   * Counts one more failed login. The failure that reaches {@code maxFailures} locks the account
   * for {@code lockDuration} and starts the count again, so after the lock the user has {@code
   * maxFailures} new tries.
   */
  public void recordFailedLogin(Instant now, int maxFailures, Duration lockDuration) {
    failedLoginCount++;
    if (failedLoginCount >= maxFailures) {
      lockedUntil = now.plus(lockDuration).truncatedTo(ChronoUnit.MICROS);
      failedLoginCount = 0;
    }
  }

  /** A successful login clears the failed count and any expired lock. */
  public void recordSuccessfulLogin(Instant now) {
    failedLoginCount = 0;
    lockedUntil = null;
    lastLoginAt = now.truncatedTo(ChronoUnit.MICROS);
  }

  public Long getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getName() {
    return name;
  }

  public PlatformRole getPlatformRole() {
    return platformRole;
  }

  public String getTotpSecret() {
    return totpSecret;
  }

  public UserStatus getStatus() {
    return status;
  }

  public int getFailedLoginCount() {
    return failedLoginCount;
  }

  public Instant getLockedUntil() {
    return lockedUntil;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getLastLoginAt() {
    return lastLoginAt;
  }
}
