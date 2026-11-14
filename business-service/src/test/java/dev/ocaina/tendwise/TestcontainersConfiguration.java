package dev.ocaina.tendwise;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a real PostgreSQL (same image as docker compose) for tests. {@code @ServiceConnection}
 * points the Spring datasource at it, so no URL or password is configured by hand.
 *
 * <p>It also gives every test context a fresh Ed25519 signing key, generated in memory, so no key
 * is ever stored in the repository.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  static final DockerImageName POSTGRES_IMAGE =
      DockerImageName.parse("pgvector/pgvector:0.8.0-pg16").asCompatibleSubstituteFor("postgres");

  /** Base64 PKCS#8, the same format as AUTH_JWT_PRIVATE_KEY. One key per test run. */
  public static final String TEST_SIGNING_KEY = generateSigningKey();

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgresContainer() {
    return new PostgreSQLContainer<>(POSTGRES_IMAGE);
  }

  @Bean
  DynamicPropertyRegistrar signingKeyProperty() {
    return registry -> registry.add("tendwise.auth.jwt-private-key", () -> TEST_SIGNING_KEY);
  }

  private static String generateSigningKey() {
    try {
      byte[] pkcs8 =
          KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded();
      return Base64.getEncoder().encodeToString(pkcs8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
