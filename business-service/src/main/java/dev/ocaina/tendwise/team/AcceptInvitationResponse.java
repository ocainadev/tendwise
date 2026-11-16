package dev.ocaina.tendwise.team;

import dev.ocaina.tendwise.user.MembershipRole;

/**
 * Answer of an accepted invitation. It sets no cookies: the web app then sends the user to the
 * login page (or, if already logged in, calls /auth/refresh to get the new business in the token).
 *
 * @param accountCreated true when the invitation created the user, false when an existing user
 *     joined
 */
public record AcceptInvitationResponse(
    String slug, String businessName, MembershipRole role, boolean accountCreated) {}
