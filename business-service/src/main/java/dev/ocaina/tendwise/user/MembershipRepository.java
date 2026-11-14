package dev.ocaina.tendwise.user;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Memberships link users to businesses. */
public interface MembershipRepository extends JpaRepository<Membership, MembershipId> {

  /** Every business the user belongs to (for the access token and /auth/me). */
  List<Membership> findByIdUserId(Long userId);
}
