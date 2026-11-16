package dev.ocaina.tendwise.team;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /auth/invitations/accept. {@code name} and {@code password} are needed only when no
 * account exists yet for the invited email; an existing user just joins the business.
 */
public record AcceptInvitationRequest(
    @NotBlank @Size(max = 100) String token,
    @Size(max = 120) String name,
    @Size(max = 1024) String password) {

  /** Never print the token or the password, for example in a log line or an error. */
  @Override
  public String toString() {
    return "AcceptInvitationRequest[token=***, name=***, password=***]";
  }
}
