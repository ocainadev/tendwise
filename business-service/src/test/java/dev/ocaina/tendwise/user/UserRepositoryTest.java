package dev.ocaina.tendwise.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.ocaina.tendwise.TestcontainersConfiguration;
import dev.ocaina.tendwise.business.BusinessRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Checks V3__users.sql constraints through the repositories and plain SQL. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class UserRepositoryTest {

  @Autowired private UserRepository users;
  @Autowired private MembershipRepository memberships;
  @Autowired private RefreshTokenRepository refreshTokens;
  @Autowired private BusinessRepository businesses;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void emailIsStoredLowercaseAndFoundByNormalizedEmail() {
    users.saveAndFlush(new User("  Ana@Example.COM ", "hash", "Ana", null));

    User found = users.findByEmail(User.normalizeEmail("ANA@example.com")).orElseThrow();
    assertThat(found.getEmail()).isEqualTo("ana@example.com");
    assertThat(found.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(found.getFailedLoginCount()).isZero();
    assertThat(found.getPlatformRole()).isNull();
    assertThat(found.getTotpSecret()).isNull();
  }

  @Test
  void emailIsUniqueIgnoringCase(CapturedOutput output) {
    users.saveAndFlush(new User("ana@example.com", "hash", "Ana", null));

    assertThatThrownBy(() -> users.saveAndFlush(new User("ANA@example.com", "hash", "Ana 2", null)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .message()
        .doesNotContain("ana@example.com");
    // logServerErrorDetail=false keeps the email out of Hibernate's error log too.
    assertThat(output).contains("users_email_key").doesNotContain("ana@example.com");
  }

  @Test
  void databaseRejectsUppercaseEmail() {
    assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO core.users (email, password_hash, name) VALUES (?, ?, ?)",
                "Ana@example.com",
                "hash",
                "Ana"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void oneUserCanBelongToSeveralBusinesses() {
    Long userId = users.saveAndFlush(new User("ana@example.com", "hash", "Ana", null)).getId();
    Long pizzaId = businesses.findBySlug("joes-pizza").orElseThrow().getId();
    Long denimId = businesses.findBySlug("denim-corner").orElseThrow().getId();

    memberships.saveAndFlush(new Membership(userId, pizzaId, MembershipRole.OWNER));
    memberships.saveAndFlush(new Membership(userId, denimId, MembershipRole.AGENT));

    assertThat(memberships.findByIdUserId(userId))
        .extracting(Membership::getBusinessId, Membership::getRole)
        .containsExactlyInAnyOrder(
            tuple(pizzaId, MembershipRole.OWNER), tuple(denimId, MembershipRole.AGENT));
  }

  @Test
  void membershipPrimaryKeyAllowsOneRolePerBusiness() {
    Long userId = users.saveAndFlush(new User("ana@example.com", "hash", "Ana", null)).getId();
    Long pizzaId = businesses.findBySlug("joes-pizza").orElseThrow().getId();
    memberships.saveAndFlush(new Membership(userId, pizzaId, MembershipRole.OWNER));

    assertThatThrownBy(() -> insertMembership(userId, pizzaId, "AGENT"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void databaseRejectsUnknownMembershipRole() {
    Long userId = users.saveAndFlush(new User("ana@example.com", "hash", "Ana", null)).getId();
    Long pizzaId = businesses.findBySlug("joes-pizza").orElseThrow().getId();

    assertThatThrownBy(() -> insertMembership(userId, pizzaId, "ADMIN"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void databaseRejectsUnknownPlatformRole() {
    assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO core.users (email, password_hash, name, platform_role)"
                    + " VALUES ('ana@example.com', 'hash', 'Ana', 'OWNER')"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void refreshTokenIsFoundByHash() {
    Long userId = users.saveAndFlush(new User("ana@example.com", "hash", "Ana", null)).getId();
    UUID familyId = UUID.randomUUID();
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
    refreshTokens.saveAndFlush(new RefreshToken(userId, "a".repeat(64), familyId, expiresAt));

    RefreshToken found = refreshTokens.findByTokenHash("a".repeat(64)).orElseThrow();
    assertThat(found.getUserId()).isEqualTo(userId);
    assertThat(found.getFamilyId()).isEqualTo(familyId);
    assertThat(found.getExpiresAt()).isEqualTo(expiresAt);
    assertThat(found.getRevokedAt()).isNull();
    assertThat(refreshTokens.findByTokenHash("b".repeat(64))).isEmpty();
  }

  private void insertMembership(Long userId, Long businessId, String role) {
    jdbc.update(
        "INSERT INTO core.memberships (user_id, business_id, role) VALUES (?, ?, ?)",
        userId,
        businessId,
        role);
  }
}
