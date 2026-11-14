package dev.ocaina.tendwise.user;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/**
 * Users belong to the platform, not to one business, so these queries have no businessId. Pass
 * emails through {@link User#normalizeEmail(String)} first: they are stored lowercase.
 */
public interface UserRepository extends JpaRepository<User, Long> {

  Optional<User> findByEmail(String email);

  /**
   * Same lookup, but locks the row until the transaction ends (login). Parallel logins for one
   * account then run one after the other, so no failed attempt is lost from the lockout count.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<User> findForUpdateByEmail(String email);
}
