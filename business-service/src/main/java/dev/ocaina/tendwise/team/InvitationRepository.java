package dev.ocaina.tendwise.team;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Every query filters by businessId, except the accept lookup by token (the token is the key). */
public interface InvitationRepository extends JpaRepository<Invitation, Long> {

  /** Pending invitations of one business, the ones that expire first first. */
  @Query(
      "select i from Invitation i where i.businessId = :businessId and i.acceptedAt is null"
          + " and i.expiresAt > :now order by i.expiresAt")
  List<Invitation> findPending(Long businessId, Instant now);

  Optional<Invitation> findByIdAndBusinessId(Long id, Long businessId);

  /**
   * Locks the row until the transaction ends, so two accepts with the same token run one after the
   * other and the second one sees it already accepted (single use).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<Invitation> findForUpdateByTokenHash(String tokenHash);

  /** Inviting the same email again replaces its pending invitation (a "resend"). */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "delete from Invitation i where i.businessId = :businessId and i.email = :email"
          + " and i.acceptedAt is null")
  int deletePending(Long businessId, String email);
}
