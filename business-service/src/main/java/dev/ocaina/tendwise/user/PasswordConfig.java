package dev.ocaina.tendwise.user;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing with Argon2id (ADR 0003). Only spring-security-crypto is used here; the login
 * itself (Spring Security filters) comes in T31.
 */
@Configuration
public class PasswordConfig {

  /** OWASP minimum for Argon2id: 19 MiB of memory, 2 iterations, 1 thread. */
  private static final int SALT_BYTES = 16;

  private static final int HASH_BYTES = 32;
  private static final int PARALLELISM = 1;
  private static final int MEMORY_KIB = 19 * 1024;
  private static final int ITERATIONS = 2;

  /**
   * The hash is a self-describing string ($argon2id$v=19$m=19456,t=2,p=1$salt$hash), so the
   * settings can be raised later without breaking existing hashes.
   */
  @Bean
  PasswordEncoder passwordEncoder() {
    return new Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);
  }
}
