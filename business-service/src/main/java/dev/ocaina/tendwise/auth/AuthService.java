package dev.ocaina.tendwise.auth;

import dev.ocaina.tendwise.auth.AuthenticatedUser.TokenMembership;
import dev.ocaina.tendwise.auth.MeResponse.MembershipView;
import dev.ocaina.tendwise.auth.MeResponse.UserView;
import dev.ocaina.tendwise.business.Business;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.user.Membership;
import dev.ocaina.tendwise.user.MembershipRepository;
import dev.ocaina.tendwise.user.RefreshToken;
import dev.ocaina.tendwise.user.RefreshTokenRepository;
import dev.ocaina.tendwise.user.User;
import dev.ocaina.tendwise.user.UserRepository;
import dev.ocaina.tendwise.user.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login, refresh and logout (ADR 0003).
 *
 * <p>Failures throw {@link AuthFailedException}. The methods use {@code noRollbackFor} so that what
 * a failure writes (a failed login count, a revoked token family) is still saved.
 *
 * <p>Logs name users by id only, never by email.
 */
@Service
public class AuthService {

  static final int MAX_FAILED_LOGINS = 5;
  static final Duration LOCK_DURATION = Duration.ofMinutes(15);
  static final Duration REFRESH_LIFETIME = Duration.ofDays(30);

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  private final UserRepository users;
  private final MembershipRepository memberships;
  private final BusinessRepository businesses;
  private final RefreshTokenRepository refreshTokens;
  private final PasswordEncoder passwordEncoder;
  private final AccessTokens accessTokens;

  /** Hash of a random password, checked when the email is unknown (see {@link #login}). */
  private final String dummyPasswordHash;

  public AuthService(
      UserRepository users,
      MembershipRepository memberships,
      BusinessRepository businesses,
      RefreshTokenRepository refreshTokens,
      PasswordEncoder passwordEncoder,
      AccessTokens accessTokens) {
    this.users = users;
    this.memberships = memberships;
    this.businesses = businesses;
    this.refreshTokens = refreshTokens;
    this.passwordEncoder = passwordEncoder;
    this.accessTokens = accessTokens;
    this.dummyPasswordHash = passwordEncoder.encode(SecureTokens.generate());
  }

  /** What a successful login or refresh hands to the controller, which sets the cookies. */
  record Session(String accessToken, String refreshToken, String csrfToken, MeResponse me) {}

  @Transactional(noRollbackFor = AuthFailedException.class)
  public Session login(String email, String password) {
    Instant now = Instant.now();
    Optional<User> found = users.findForUpdateByEmail(User.normalizeEmail(email));
    if (found.isEmpty()) {
      // Same Argon2 work as for a real user, so the response time does not reveal
      // whether the email exists.
      passwordEncoder.matches(password, dummyPasswordHash);
      throw AuthFailedException.badCredentials();
    }

    User user = found.get();
    // Checked before the lock and status tests, for the same timing reason.
    boolean passwordMatches = passwordEncoder.matches(password, user.getPasswordHash());
    if (user.isLocked(now)) {
      log.info("Login refused: user {} is locked", user.getId());
      throw AuthFailedException.badCredentials();
    }
    if (!passwordMatches) {
      user.recordFailedLogin(now, MAX_FAILED_LOGINS, LOCK_DURATION);
      if (user.isLocked(now)) {
        log.warn("User {} locked after {} failed logins", user.getId(), MAX_FAILED_LOGINS);
      }
      throw AuthFailedException.badCredentials();
    }
    if (user.getStatus() != UserStatus.ACTIVE) {
      log.info("Login refused: user {} is disabled", user.getId());
      throw AuthFailedException.badCredentials();
    }

    user.recordSuccessfulLogin(now);
    // Each login starts a new token family.
    return startSession(user, UUID.randomUUID(), now);
  }

  /**
   * Swaps a refresh token for a new one (same family) and a new access token. A token that was
   * already used means a copy of it exists somewhere else: the whole family is revoked, which logs
   * out both the user and whoever holds the copy.
   */
  @Transactional(noRollbackFor = AuthFailedException.class)
  public Session refresh(String rawRefreshToken) {
    Instant now = Instant.now();
    RefreshToken token =
        refreshTokens
            .findForUpdateByTokenHash(SecureTokens.sha256Hex(rawRefreshToken))
            .orElseThrow(AuthFailedException::sessionEnded);

    if (token.getRevokedAt() != null) {
      refreshTokens.revokeFamily(token.getFamilyId(), now);
      log.warn("Reused refresh token for user {}: token family revoked", token.getUserId());
      throw AuthFailedException.sessionEnded();
    }
    if (token.isExpired(now)) {
      throw AuthFailedException.sessionEnded();
    }
    Optional<User> user =
        users.findById(token.getUserId()).filter(u -> u.getStatus() == UserStatus.ACTIVE);
    if (user.isEmpty()) {
      refreshTokens.revokeFamily(token.getFamilyId(), now);
      throw AuthFailedException.sessionEnded();
    }

    token.revoke(now);
    return startSession(user.get(), token.getFamilyId(), now);
  }

  /** Ends the login the refresh token belongs to. An unknown or missing token is ignored. */
  @Transactional
  public void logout(String rawRefreshToken) {
    if (rawRefreshToken == null) {
      return;
    }
    refreshTokens
        .findByTokenHash(SecureTokens.sha256Hex(rawRefreshToken))
        .ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), Instant.now()));
  }

  /** Read fresh from the database; empty if the user was deleted or disabled. */
  @Transactional(readOnly = true)
  public Optional<MeResponse> currentUser(long userId) {
    return users
        .findById(userId)
        .filter(user -> user.getStatus() == UserStatus.ACTIVE)
        .map(user -> toMe(user, membershipsOf(user)));
  }

  private Session startSession(User user, UUID familyId, Instant now) {
    String refreshToken = SecureTokens.generate();
    refreshTokens.save(
        new RefreshToken(
            user.getId(),
            SecureTokens.sha256Hex(refreshToken),
            familyId,
            now.plus(REFRESH_LIFETIME)));

    List<MembershipView> views = membershipsOf(user);
    List<TokenMembership> tokenMemberships =
        views.stream().map(view -> new TokenMembership(view.slug(), view.role())).toList();
    AuthenticatedUser principal =
        new AuthenticatedUser(user.getId(), user.getPlatformRole(), tokenMemberships);

    return new Session(
        accessTokens.issue(principal, now),
        refreshToken,
        SecureTokens.generate(),
        toMe(user, views));
  }

  /** The user's businesses with slug and name, ordered by slug. */
  private List<MembershipView> membershipsOf(User user) {
    List<Membership> list = memberships.findByIdUserId(user.getId());
    Map<Long, Business> businessById =
        businesses.findAllById(list.stream().map(Membership::getBusinessId).toList()).stream()
            .collect(Collectors.toMap(Business::getId, Function.identity()));
    return list.stream()
        .map(membership -> {
          Business business = businessById.get(membership.getBusinessId());
          return new MembershipView(business.getSlug(), business.getName(), membership.getRole());
        })
        .sorted(Comparator.comparing(MembershipView::slug))
        .toList();
  }

  private static MeResponse toMe(User user, List<MembershipView> memberships) {
    return new MeResponse(
        new UserView(user.getId(), user.getEmail(), user.getName()),
        user.getPlatformRole(),
        memberships);
  }
}
