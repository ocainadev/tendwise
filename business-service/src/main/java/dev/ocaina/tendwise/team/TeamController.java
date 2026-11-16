package dev.ocaina.tendwise.team;

import dev.ocaina.tendwise.panel.CurrentMembership;
import dev.ocaina.tendwise.panel.OwnerOnly;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The team page (T34). OWNER only; PanelGuard checks the business and the role first. */
@RestController
@OwnerOnly
@SecurityRequirements // Session cookies, not X-Api-Key.
public class TeamController {

  private final TeamService teamService;

  public TeamController(TeamService teamService) {
    this.teamService = teamService;
  }

  @GetMapping("/api/v1/panel/{slug}/team")
  List<MemberResponse> list(CurrentMembership membership) {
    return teamService.list(membership.businessId());
  }

  /** 404 for a user who is not in this business; 409 when it would leave no OWNER. */
  @PatchMapping("/api/v1/panel/{slug}/team/{userId}")
  MemberResponse changeRole(
      CurrentMembership membership,
      @PathVariable long userId,
      @Valid @RequestBody ChangeRoleRequest body) {
    return teamService.changeRole(membership.businessId(), userId, body.role());
  }

  /** 404 for a user who is not in this business; 409 for the last OWNER. */
  @DeleteMapping("/api/v1/panel/{slug}/team/{userId}")
  ResponseEntity<Void> remove(CurrentMembership membership, @PathVariable long userId) {
    teamService.remove(membership.businessId(), userId);
    return ResponseEntity.noContent().build();
  }
}
