package dev.ocaina.tendwise.team;

import dev.ocaina.tendwise.user.MembershipRole;
import java.time.Instant;

/** A pending invitation, as the team page lists it. The token is never returned. */
public record InvitationResponse(long id, String email, MembershipRole role, Instant expiresAt) {

  static InvitationResponse of(Invitation invitation) {
    return new InvitationResponse(
        invitation.getId(), invitation.getEmail(), invitation.getRole(), invitation.getExpiresAt());
  }
}
