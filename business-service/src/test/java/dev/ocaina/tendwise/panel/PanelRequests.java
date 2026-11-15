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
final class PanelRequests {

  static final String CSRF = "test-csrf-token";

  private PanelRequests() {}

  /** A business staff member with one membership. */
  static String tokenFor(AccessTokens accessTokens, String slug, MembershipRole role) {
    AuthenticatedUser user =
        new AuthenticatedUser(1_000_001L, null, List.of(new TokenMembership(slug, role)));
    return accessTokens.issue(user, Instant.now());
  }

  /** The platform operator, with no memberships. */
  static String adminToken(AccessTokens accessTokens) {
    AuthenticatedUser user = new AuthenticatedUser(1_000_002L, PlatformRole.ADMIN, List.of());
    return accessTokens.issue(user, Instant.now());
  }

  /** Adds the access cookie, and the CSRF cookie and header the web app always sends. */
  static MockHttpServletRequestBuilder withSession(
      MockHttpServletRequestBuilder request, String accessToken) {
    return request
        .cookie(new Cookie("tendwise_access", accessToken), new Cookie("tendwise_csrf", CSRF))
        .header("X-CSRF-Token", CSRF);
  }
}
