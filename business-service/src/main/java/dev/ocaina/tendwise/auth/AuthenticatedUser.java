package dev.ocaina.tendwise.auth;

import dev.ocaina.tendwise.user.MembershipRole;
import dev.ocaina.tendwise.user.PlatformRole;
import java.util.List;

/**
 * Who sent the request, as read from a valid access token. It is the Spring Security principal, so
 * a controller can take it with {@code @AuthenticationPrincipal}.
 *
 * @param platformRole ADMIN for the operator, null for business staff
 */
public record AuthenticatedUser(
    long userId, PlatformRole platformRole, List<TokenMembership> memberships) {

  /** One business the user belongs to, as carried in the token. */
  public record TokenMembership(String slug, MembershipRole role) {}
}
