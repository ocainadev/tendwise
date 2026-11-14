package dev.ocaina.tendwise.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Users belong to the platform, not to one business, so these queries have no businessId. Pass
 * emails through {@link User#normalizeEmail(String)} first: they are stored lowercase.
 */
public interface UserRepository extends JpaRepository<User, Long> {

  Optional<User> findByEmail(String email);
}
