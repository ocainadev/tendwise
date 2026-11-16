package dev.ocaina.tendwise.inbox;

import dev.ocaina.tendwise.auth.AuthenticatedUser;
import dev.ocaina.tendwise.conversation.ConversationState;
import dev.ocaina.tendwise.panel.CurrentMembership;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The business panel's inbox, read side (docs/architecture.md, "Panel API", T43). Open to every
 * member (AGENT or OWNER); PanelGuard has already checked the slug, and every query uses {@link
 * CurrentMembership#businessId()}.
 *
 * <p>The tabs map to filters: "Precisa de você" = {@code state=WAITING}, "Comigo" = {@code
 * state=HUMAN&assignee=me}, "IA atendendo" = {@code state=AI}, "Resolvidas" = {@code
 * state=RESOLVED}.
 */
@RestController
@RequestMapping("/api/v1/panel/{slug}/conversations")
class InboxController {

  private final InboxService service;

  InboxController(InboxService service) {
    this.service = service;
  }

  /**
   * Conversations, newest message first, with the tab counts.
   *
   * @param assignee "me" or a user id
   * @param q text searched in the messages (case-insensitive)
   * @param cursor nextCursor of the previous page
   * @param limit page size, 1 to 100
   */
  @GetMapping
  ConversationListResponse list(
      CurrentMembership membership,
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) ConversationState state,
      @RequestParam(required = false) String assignee,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "" + InboxService.DEFAULT_PAGE_SIZE) int limit) {
    return service.list(membership.businessId(), user.userId(), state, assignee, q, cursor, limit);
  }

  /** One conversation with every message (internal notes included) and its handoffs. */
  @GetMapping("/{id}")
  ConversationDetailResponse detail(CurrentMembership membership, @PathVariable UUID id) {
    return service.detail(membership.businessId(), id);
  }

  @ExceptionHandler(InvalidInboxRequestException.class)
  ProblemDetail handleInvalidRequest(InvalidInboxRequestException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    problem.setTitle("Invalid request");
    return problem;
  }
}
