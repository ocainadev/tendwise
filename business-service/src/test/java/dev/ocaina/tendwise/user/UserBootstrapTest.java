package dev.ocaina.tendwise.user;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ocaina.tendwise.TestcontainersConfiguration;
import dev.ocaina.tendwise.business.BusinessRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Runs the startup bootstrap against a real database, built by hand with test settings. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class UserBootstrapTest {

  private static final String ADMIN_EMAIL = "Operator@Example.com";
  private static final String ADMIN_PASSWORD = "admin-test-password-123";
  private static final String DEMO_PASSWORD = "demo-test-password-456";

  private final PasswordEncoder encoder = new PasswordConfig().passwordEncoder();

  @Autowired private UserRepository users;
  @Autowired private MembershipRepository memberships;
  @Autowired private BusinessRepository businesses;

  @Test
  void createsAdminAndOneOwnerAndAgentPerDemoBusiness() {
    bootstrap(ADMIN_EMAIL, ADMIN_PASSWORD, DEMO_PASSWORD).run(null);

    User admin = users.findByEmail("operator@example.com").orElseThrow();
    assertThat(admin.getPlatformRole()).isEqualTo(PlatformRole.ADMIN);
    assertThat(admin.getPasswordHash()).isNotEqualTo(ADMIN_PASSWORD).startsWith("$argon2id$");
    assertThat(encoder.matches(ADMIN_PASSWORD, admin.getPasswordHash())).isTrue();
    assertThat(memberships.findByIdUserId(admin.getId())).isEmpty();

    Long pizzaId = businesses.findBySlug("joes-pizza").orElseThrow().getId();
    User owner = users.findByEmail("owner@joes-pizza.test").orElseThrow();
    assertThat(owner.getPlatformRole()).isNull();
    assertThat(encoder.matches(DEMO_PASSWORD, owner.getPasswordHash())).isTrue();
    assertThat(memberships.findByIdUserId(owner.getId()))
        .singleElement()
        .satisfies(m -> {
          assertThat(m.getBusinessId()).isEqualTo(pizzaId);
          assertThat(m.getRole()).isEqualTo(MembershipRole.OWNER);
        });

    User agent = users.findByEmail("agent@denim-corner.test").orElseThrow();
    assertThat(memberships.findByIdUserId(agent.getId()))
        .singleElement()
        .extracting(Membership::getRole)
        .isEqualTo(MembershipRole.AGENT);

    assertThat(users.count()).isEqualTo(5);
    assertThat(memberships.count()).isEqualTo(4);
  }

  @Test
  void runningTwiceChangesNothing() {
    bootstrap(ADMIN_EMAIL, ADMIN_PASSWORD, DEMO_PASSWORD).run(null);
    String adminHash = users.findByEmail("operator@example.com").orElseThrow().getPasswordHash();

    // A restart with a new password in the environment does not reset existing users.
    bootstrap(ADMIN_EMAIL, "another-password-789", DEMO_PASSWORD).run(null);

    assertThat(users.count()).isEqualTo(5);
    assertThat(memberships.count()).isEqualTo(4);
    assertThat(users.findByEmail("operator@example.com").orElseThrow().getPasswordHash())
        .isEqualTo(adminHash);
  }

  @Test
  void skipsWithAWarningWhenVariablesAreUnset(CapturedOutput output) {
    bootstrap("", null, "").run(null);

    assertThat(users.count()).isZero();
    assertThat(output)
        .contains("PLATFORM_ADMIN_EMAIL or PLATFORM_ADMIN_PASSWORD is not set")
        .contains("DEMO_USERS_PASSWORD is not set");
  }

  @Test
  void doesNotPromoteAnExistingUser(CapturedOutput output) {
    users.saveAndFlush(new User("operator@example.com", "existing-hash", "Ana", null));

    bootstrap(ADMIN_EMAIL, ADMIN_PASSWORD, "").run(null);

    User user = users.findByEmail("operator@example.com").orElseThrow();
    assertThat(user.getPlatformRole()).isNull();
    assertThat(user.getPasswordHash()).isEqualTo("existing-hash");
    assertThat(output).contains("exists but is not a platform admin");
  }

  @Test
  void neverLogsPasswordsOrEmails(CapturedOutput output) {
    bootstrap(ADMIN_EMAIL, ADMIN_PASSWORD, DEMO_PASSWORD).run(null);

    assertThat(output).contains("Platform admin created").contains("Demo owner created");
    assertThat(output.toString().toLowerCase())
        .doesNotContain(ADMIN_PASSWORD)
        .doesNotContain(DEMO_PASSWORD)
        .doesNotContain("operator@example.com")
        .doesNotContain("@joes-pizza.test")
        .doesNotContain("@denim-corner.test")
        .doesNotContain("$argon2id$");
  }

  private UserBootstrap bootstrap(String adminEmail, String adminPassword, String demoPassword) {
    return new UserBootstrap(
        users, memberships, businesses, encoder, adminEmail, adminPassword, demoPassword);
  }
}
