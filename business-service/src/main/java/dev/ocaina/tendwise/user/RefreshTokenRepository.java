package dev.ocaina.tendwise.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Refresh tokens are looked up by the SHA-256 hash of the token the browser sends (T31). */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);
}
