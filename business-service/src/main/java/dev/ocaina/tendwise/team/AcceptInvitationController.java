package dev.ocaina.tendwise.team;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public: the invitee may have no account yet. The token travels in the body, never in the path
 * (docs/architecture.md). No CSRF header is needed: this route reads no cookies.
 */
@RestController
@SecurityRequirements
public class AcceptInvitationController {

  private final InvitationService invitationService;

  public AcceptInvitationController(InvitationService invitationService) {
    this.invitationService = invitationService;
  }

  /** 400 for an unknown, expired, revoked or used token (one answer for all). */
  @PostMapping("/auth/invitations/accept")
  AcceptInvitationResponse accept(@Valid @RequestBody AcceptInvitationRequest body) {
    return invitationService.accept(body.token(), body.name(), body.password());
  }
}
