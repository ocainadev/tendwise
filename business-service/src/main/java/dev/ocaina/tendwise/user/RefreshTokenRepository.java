package dev.ocaina.tendwise.user;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Refresh tokens are looked up by the SHA-256 hash of the token the browser sends. */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /**
   * Same lookup, but locks the row until the transaction ends. Two refreshes with the same token
   * then run one after the other: the second sees the token already revoked (reuse).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<RefreshToken> findForUpdateByTokenHash(String tokenHash);

  /**
   * Revokes every still-valid token of one login (logout, or a reused token). Clears the
   * persistence context afterwards, so no stale entity says a token is still valid.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update RefreshToken t set t.revokedAt = :now"
          + " where t.familyId = :familyId and t.revokedAt is null")
  int revokeFamily(UUID familyId, Instant now);
}
