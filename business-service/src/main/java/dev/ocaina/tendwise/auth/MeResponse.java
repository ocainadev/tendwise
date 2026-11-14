package dev.ocaina.tendwise.auth;

import dev.ocaina.tendwise.user.MembershipRole;
import dev.ocaina.tendwise.user.PlatformRole;
import java.util.List;

/**
 * Body of GET /auth/me, and of a successful login or refresh.
 *
 * @param platformRole ADMIN for the operator, null for business staff
 */
public record MeResponse(
    UserView user, PlatformRole platformRole, List<MembershipView> memberships) {

  public record UserView(long id, String email, String name) {}

  /** One business the user can open in the panel, and their role there. */
  public record MembershipView(String slug, String name, MembershipRole role) {}
}
