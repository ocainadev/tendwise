package dev.ocaina.tendwise.team;

import dev.ocaina.tendwise.auth.AuthenticatedUser;
import dev.ocaina.tendwise.panel.CurrentMembership;
import dev.ocaina.tendwise.panel.OwnerOnly;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Pending invitations of the business (T34). OWNER only. */
@RestController
@OwnerOnly
@SecurityRequirements // Session cookies, not X-Api-Key.
public class InvitationController {

  private final InvitationService invitationService;

  public InvitationController(InvitationService invitationService) {
    this.invitationService = invitationService;
  }

  @GetMapping("/api/v1/panel/{slug}/invitations")
  List<InvitationResponse> list(CurrentMembership membership) {
    return invitationService.listPending(membership.businessId());
  }

  /** 201 and the link is emailed. 409 if the email already belongs to a member. */
  @PostMapping("/api/v1/panel/{slug}/invitations")
  ResponseEntity<InvitationResponse> create(
      CurrentMembership membership,
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateInvitationRequest body) {
    InvitationResponse created =
        invitationService.create(membership.businessId(), user.userId(), body.email(), body.role());
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  /** Revokes a pending invitation: its link stops working. */
  @DeleteMapping("/api/v1/panel/{slug}/invitations/{id}")
  ResponseEntity<Void> revoke(CurrentMembership membership, @PathVariable long id) {
    invitationService.revoke(membership.businessId(), id);
    return ResponseEntity.noContent().build();
  }
}
