package dev.ocaina.tendwise.inbox;

import dev.ocaina.tendwise.auth.AuthenticatedUser;
import dev.ocaina.tendwise.inbox.ConversationDetailResponse.MessageItem;
import dev.ocaina.tendwise.panel.CurrentMembership;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The inbox actions (docs/architecture.md, "Panel API", T44). Open to every member (AGENT or
 * OWNER); PanelGuard has already checked the slug, and every call uses {@link
 * CurrentMembership#businessId()}. Another business's conversation answers 404.
 *
 * <ul>
 *   <li>takeover, assign, release, resolve: 200 with the conversation (as {@code GET …/{id}}); 409
 *       when its state does not allow it (Problem Details with state, assigneeUserId and
 *       assigneeName).
 *   <li>messages: 201 with the stored message; 403 for a reply by someone who is not the assignee,
 *       409 for a reply when nobody has taken the conversation over.
 *   <li>read, typing: 204.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/panel/{slug}/conversations/{id}")
class InboxActionController {

  private final InboxActionService service;

  InboxActionController(InboxActionService service) {
    this.service = service;
  }

  /** AI or WAITING → HUMAN, assigned to the caller. 409 if someone else has it. */
  @PostMapping("/takeover")
  ConversationDetailResponse takeOver(
      CurrentMembership membership,
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    return service.takeOver(membership.businessId(), id, user.userId());
  }

  /** A reply to the customer (assignee only) or, with internal = true, a note for colleagues. */
  @PostMapping("/messages")
  @ResponseStatus(HttpStatus.CREATED)
  MessageItem sendMessage(
      CurrentMembership membership,
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id,
      @Valid @RequestBody StaffMessageRequest request) {
    return service.sendMessage(membership.businessId(), id, user.userId(), request);
  }

  /** Gives the conversation to a member of the business (HUMAN). */
  @PostMapping("/assign")
  ConversationDetailResponse assign(
      CurrentMembership membership,
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id,
      @Valid @RequestBody AssignRequest request) {
    return service.assign(membership.businessId(), id, user.userId(), request.userId());
  }

  /** WAITING or HUMAN → AI. */
  @PostMapping("/release")
  ConversationDetailResponse release(
      CurrentMembership membership,
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    return service.release(membership.businessId(), id, user.userId());
  }

  /** AI, WAITING or HUMAN → RESOLVED. */
  @PostMapping("/resolve")
  ConversationDetailResponse resolve(
      CurrentMembership membership,
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    return service.resolve(membership.businessId(), id, user.userId());
  }

  /** No unread customer messages left; sends conversation.read to the panel. */
  @PostMapping("/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void markRead(CurrentMembership membership, @PathVariable UUID id) {
    service.markRead(membership.businessId(), id);
  }

  /** The assignee is typing: staff.typing, at most one every 3 seconds. Stores nothing. */
  @PostMapping("/typing")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void typing(
      CurrentMembership membership,
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    service.typing(membership.businessId(), id, user.userId());
  }
}
