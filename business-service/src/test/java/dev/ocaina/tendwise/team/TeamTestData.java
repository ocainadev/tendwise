package dev.ocaina.tendwise.team;

import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.user.Membership;
import dev.ocaina.tendwise.user.MembershipRepository;
import dev.ocaina.tendwise.user.MembershipRole;
import dev.ocaina.tendwise.user.User;
import dev.ocaina.tendwise.user.UserRepository;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Creates a fresh business and users for each test, so the tests never share a team (the seeded
 * businesses get owners from other test classes).
 */
final class TeamTestData {

  static final String PASSWORD = "correct-horse-battery-staple";

  private final JdbcTemplate jdbc;
  private final BusinessRepository businesses;
  private final UserRepository users;
  private final MembershipRepository memberships;
  private final PasswordEncoder passwordEncoder;

  TeamTestData(
      JdbcTemplate jdbc,
      BusinessRepository businesses,
      UserRepository users,
      MembershipRepository memberships,
      PasswordEncoder passwordEncoder) {
    this.jdbc = jdbc;
    this.businesses = businesses;
    this.users = users;
    this.memberships = memberships;
    this.passwordEncoder = passwordEncoder;
  }

  /** A new business; returns its slug. */
  String business() {
    String slug = "team-" + UUID.randomUUID().toString().substring(0, 8);
    jdbc.update(
        """
        INSERT INTO core.businesses
          (slug, name, category, default_language, timezone, publishable_key)
        VALUES (?, ?, 'shop', 'pt', 'America/Sao_Paulo', ?)
        """,
        slug,
        "Shop " + slug,
        "pk_test_" + slug);
    return slug;
  }

  long businessId(String slug) {
    return businesses.findBySlug(slug).orElseThrow().getId();
  }

  /** A new user (password {@link #PASSWORD}) who is a member of the business. */
  User member(String slug, String name, MembershipRole role) {
    User user = user(name);
    memberships.save(new Membership(user.getId(), businessId(slug), role));
    return user;
  }

  /** A new user with no membership. */
  User user(String name) {
    return users.save(new User(uniqueEmail(name), passwordEncoder.encode(PASSWORD), name, null));
  }

  static String uniqueEmail(String name) {
    return name.toLowerCase() + "-" + UUID.randomUUID() + "@example.test";
  }
}
