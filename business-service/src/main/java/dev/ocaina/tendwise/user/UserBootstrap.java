package dev.ocaina.tendwise.user;

import dev.ocaina.tendwise.business.Business;
import dev.ocaina.tendwise.business.BusinessRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first users on startup, so no password or hash ever lives in a migration:
 *
 * <ul>
 *   <li>the platform admin, from PLATFORM_ADMIN_EMAIL and PLATFORM_ADMIN_PASSWORD;
 *   <li>when DEMO_USERS_PASSWORD is set, an owner and an agent for each demo business
 *       (owner@joes-pizza.test, agent@joes-pizza.test, owner@denim-corner.test, ...).
 * </ul>
 *
 * <p>It is safe to run on every start: users that already exist are left unchanged (their password
 * is not reset). Logs never contain a password or an email.
 */
@Component
public class UserBootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(UserBootstrap.class);

  /** The businesses seeded by V2__seed.sql. */
  static final List<String> DEMO_BUSINESS_SLUGS = List.of("joes-pizza", "denim-corner");

  private final UserRepository users;
  private final MembershipRepository memberships;
  private final BusinessRepository businesses;
  private final PasswordEncoder passwordEncoder;
  private final String adminEmail;
  private final String adminPassword;
  private final String demoUsersPassword;

  public UserBootstrap(
      UserRepository users,
      MembershipRepository memberships,
      BusinessRepository businesses,
      PasswordEncoder passwordEncoder,
      @Value("${tendwise.bootstrap.platform-admin-email}") String adminEmail,
      @Value("${tendwise.bootstrap.platform-admin-password}") String adminPassword,
      @Value("${tendwise.bootstrap.demo-users-password}") String demoUsersPassword) {
    this.users = users;
    this.memberships = memberships;
    this.businesses = businesses;
    this.passwordEncoder = passwordEncoder;
    this.adminEmail = adminEmail;
    this.adminPassword = adminPassword;
    this.demoUsersPassword = demoUsersPassword;
  }

  @Override
  public void run(ApplicationArguments args) {
    createPlatformAdmin();
    createDemoUsers();
  }

  private void createPlatformAdmin() {
    if (isBlank(adminEmail) || isBlank(adminPassword)) {
      log.warn(
          "PLATFORM_ADMIN_EMAIL or PLATFORM_ADMIN_PASSWORD is not set: no platform admin created.");
      return;
    }

    Optional<User> existing = users.findByEmail(User.normalizeEmail(adminEmail));
    if (existing.isPresent()) {
      if (existing.get().getPlatformRole() != PlatformRole.ADMIN) {
        log.warn("The PLATFORM_ADMIN_EMAIL user exists but is not a platform admin; not changed.");
      }
      return;
    }

    String hash = passwordEncoder.encode(adminPassword);
    users.save(new User(adminEmail, hash, "Platform admin", PlatformRole.ADMIN));
    log.info("Platform admin created from PLATFORM_ADMIN_EMAIL.");
  }

  private void createDemoUsers() {
    if (isBlank(demoUsersPassword)) {
      log.info("DEMO_USERS_PASSWORD is not set: no demo users created.");
      return;
    }

    for (String slug : DEMO_BUSINESS_SLUGS) {
      Optional<Business> business = businesses.findBySlug(slug);
      if (business.isEmpty()) {
        log.warn("Demo business {} not found: no demo users created for it.", slug);
        continue;
      }
      createDemoUser(business.get(), MembershipRole.OWNER);
      createDemoUser(business.get(), MembershipRole.AGENT);
    }
  }

  /** For example owner@joes-pizza.test (the .test domain is reserved and never delivers mail). */
  private void createDemoUser(Business business, MembershipRole role) {
    String roleName = role.name().toLowerCase(Locale.ROOT);
    String email = roleName + "@" + business.getSlug() + ".test";

    User user = users.findByEmail(email).orElse(null);
    if (user == null) {
      String hash = passwordEncoder.encode(demoUsersPassword);
      user = users.save(new User(email, hash, business.getName() + " " + roleName, null));
      log.info("Demo {} created for {}.", roleName, business.getSlug());
    }

    MembershipId membershipId = new MembershipId(user.getId(), business.getId());
    if (!memberships.existsById(membershipId)) {
      memberships.save(new Membership(user.getId(), business.getId(), role));
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
