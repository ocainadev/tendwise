package dev.ocaina.tendwise.user;

import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** Memberships link users to businesses. */
public interface MembershipRepository extends JpaRepository<Membership, MembershipId> {

  /** Every business the user belongs to (for the access token and /auth/me). */
  List<Membership> findByIdUserId(Long userId);

  /** Every member of one business (team API). */
  List<Membership> findByIdBusinessId(Long businessId);

  /**
   * The business's owners, locked until the transaction ends. A role change or removal takes this
   * lock first, so two owners demoting each other at the same time run one after the other and the
   * second one sees that only one owner is left.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<Membership> findForUpdateByIdBusinessIdAndRole(Long businessId, MembershipRole role);
}
