package dev.ocaina.tendwise.team;

import dev.ocaina.tendwise.user.MembershipRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /api/v1/panel/{slug}/invitations. */
public record CreateInvitationRequest(
    @NotBlank @Email @Size(max = 254) String email, @NotNull MembershipRole role) {

  /** Never print the full email, for example in a log line or an error. */
  @Override
  public String toString() {
    return "CreateInvitationRequest[email=***, role=" + role + "]";
  }
}
