package dev.ocaina.tendwise.panel;

import dev.ocaina.tendwise.auth.AccessTokens;
import dev.ocaina.tendwise.auth.AuthenticatedUser;
import dev.ocaina.tendwise.auth.AuthenticatedUser.TokenMembership;
import dev.ocaina.tendwise.user.MembershipRole;
import dev.ocaina.tendwise.user.PlatformRole;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Builds logged-in requests for the panel tests. The guard only reads the access token, so the
 * tests sign one directly instead of going through /auth/login.
 */
public final class PanelRequests {

  public static final String CSRF = "test-csrf-token";

  private PanelRequests() {}

  /** A business staff member with one membership. */
  public static String tokenFor(AccessTokens accessTokens, String slug, MembershipRole role) {
    return tokenFor(accessTokens, 1_000_001L, slug, role);
  }

  /** The same, for a user that exists in the database (needed when the route stores the id). */
  public static String tokenFor(
      AccessTokens accessTokens, long userId, String slug, MembershipRole role) {
    AuthenticatedUser user =
        new AuthenticatedUser(userId, null, List.of(new TokenMembership(slug, role)));
    return accessTokens.issue(user, Instant.now());
  }

  /** The platform operator, with no memberships. */
  public static String adminToken(AccessTokens accessTokens) {
    AuthenticatedUser user = new AuthenticatedUser(1_000_002L, PlatformRole.ADMIN, List.of());
    return accessTokens.issue(user, Instant.now());
  }

  /** Adds the access cookie, and the CSRF cookie and header the web app always sends. */
  public static MockHttpServletRequestBuilder withSession(
      MockHttpServletRequestBuilder request, String accessToken) {
    return request
        .cookie(new Cookie("tendwise_access", accessToken), new Cookie("tendwise_csrf", CSRF))
        .header("X-CSRF-Token", CSRF);
  }
}
