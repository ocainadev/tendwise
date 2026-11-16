package dev.ocaina.tendwise.team;

import dev.ocaina.tendwise.auth.SecureTokens;
import dev.ocaina.tendwise.business.Business;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.email.EmailSender;
import dev.ocaina.tendwise.email.InvitationEmail;
import dev.ocaina.tendwise.shared.BadRequestException;
import dev.ocaina.tendwise.shared.ConflictException;
import dev.ocaina.tendwise.shared.NotFoundException;
import dev.ocaina.tendwise.user.Membership;
import dev.ocaina.tendwise.user.MembershipId;
import dev.ocaina.tendwise.user.MembershipRepository;
import dev.ocaina.tendwise.user.MembershipRole;
import dev.ocaina.tendwise.user.User;
import dev.ocaina.tendwise.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Team invitations (T34): an OWNER invites an email with a role, and the invitee accepts with the
 * token from the email.
 *
 * <p>Logs name invitations and users by id only, never by email or token.
 */
@Service
public class InvitationService {

  static final Duration LIFETIME = Duration.ofDays(7);

  /** NIST SP 800-63B minimum for a password the user chooses. */
  static final int MIN_PASSWORD_LENGTH = 8;

  /** The web page that reads the token from the link and calls /auth/invitations/accept. */
  static final String ACCEPT_PAGE = "/invitations/accept";

  private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

  private final InvitationRepository invitations;
  private final BusinessRepository businesses;
  private final UserRepository users;
  private final MembershipRepository memberships;
  private final PasswordEncoder passwordEncoder;
  private final EmailSender emailSender;
  private final String webUrl;

  public InvitationService(
      InvitationRepository invitations,
      BusinessRepository businesses,
      UserRepository users,
      MembershipRepository memberships,
      PasswordEncoder passwordEncoder,
      EmailSender emailSender,
      @Value("${tendwise.email.web-url}") String webUrl) {
    this.invitations = invitations;
    this.businesses = businesses;
    this.users = users;
    this.memberships = memberships;
    this.passwordEncoder = passwordEncoder;
    this.emailSender = emailSender;
    this.webUrl = webUrl.endsWith("/") ? webUrl.substring(0, webUrl.length() - 1) : webUrl;
  }

  /**
   * Creates the invitation and emails the link. Inviting an email that already has a pending
   * invitation to this business replaces it (the old link stops working). Someone who is already a
   * member gets 409.
   */
  @Transactional
  public InvitationResponse create(
      long businessId, long invitedBy, String email, MembershipRole role) {
    String normalized = User.normalizeEmail(email);
    Optional<User> existing = users.findByEmail(normalized);
    if (existing.isPresent()
        && memberships.existsById(new MembershipId(existing.get().getId(), businessId))) {
      throw new ConflictException(
          "Already a member", "This person is already a member of the team.");
    }
    Business business = businesses.findById(businessId).orElseThrow();

    invitations.deletePending(businessId, normalized);
    String token = SecureTokens.generate();
    Invitation invitation =
        invitations.save(
            new Invitation(
                businessId,
                normalized,
                role,
                SecureTokens.sha256Hex(token),
                invitedBy,
                Instant.now().plus(LIFETIME)));

    emailSender.sendInvitation(
        new InvitationEmail(
            normalized, business.getName(), role, webUrl + ACCEPT_PAGE + "#token=" + token));
    log.info(
        "Invitation {} created for business {} by user {}",
        invitation.getId(),
        businessId,
        invitedBy);
    return InvitationResponse.of(invitation);
  }

  @Transactional(readOnly = true)
  public List<InvitationResponse> listPending(long businessId) {
    return invitations.findPending(businessId, Instant.now()).stream()
        .map(InvitationResponse::of)
        .toList();
  }

  /** Deletes a pending invitation, so its link stops working. 404 if it is not this business's. */
  @Transactional
  public void revoke(long businessId, long invitationId) {
    Invitation invitation =
        invitations
            .findByIdAndBusinessId(invitationId, businessId)
            .filter(i -> i.getAcceptedAt() == null)
            .orElseThrow(
                () -> new NotFoundException("Invitation not found", "No pending invitation."));
    invitations.delete(invitation);
    log.info("Invitation {} revoked in business {}", invitationId, businessId);
  }

  /**
   * Accepts an invitation. With no account for the email yet, it creates the user with {@code name}
   * and {@code password}. An existing user just joins the business: name and password are ignored
   * and the password is never changed. A user who is already a member keeps their current role.
   *
   * <p>An unknown, expired, revoked or already used token all get the same 400.
   */
  @Transactional
  public AcceptInvitationResponse accept(String token, String name, String password) {
    Instant now = Instant.now();
    Invitation invitation =
        invitations
            .findForUpdateByTokenHash(SecureTokens.sha256Hex(token))
            .filter(i -> i.isPending(now))
            .orElseThrow(InvitationService::invalidInvitation);
    Business business =
        businesses
            .findById(invitation.getBusinessId())
            .orElseThrow(InvitationService::invalidInvitation);

    Optional<User> existing = users.findByEmail(invitation.getEmail());
    boolean accountCreated = existing.isEmpty();
    User user = existing.orElseGet(() -> createUser(invitation.getEmail(), name, password));

    MembershipId membershipId = new MembershipId(user.getId(), business.getId());
    Membership membership =
        memberships
            .findById(membershipId)
            .orElseGet(() ->
                memberships.save(
                    new Membership(user.getId(), business.getId(), invitation.getRole())));
    invitation.markAccepted(now);

    log.info(
        "Invitation {} accepted by user {} (new account: {})",
        invitation.getId(),
        user.getId(),
        accountCreated);
    return new AcceptInvitationResponse(
        business.getSlug(), business.getName(), membership.getRole(), accountCreated);
  }

  private User createUser(String email, String name, String password) {
    if (name == null
        || name.isBlank()
        || password == null
        || password.length() < MIN_PASSWORD_LENGTH) {
      // The transaction rolls back, so the invitation stays pending and the user can try again.
      throw new BadRequestException(
          "Account details required",
          "To create your account, send your name and a password of at least "
              + MIN_PASSWORD_LENGTH
              + " characters.");
    }
    return users.save(new User(email, passwordEncoder.encode(password), name.trim(), null));
  }

  /** The same answer for every token that cannot be used, so none of them can be told apart. */
  private static BadRequestException invalidInvitation() {
    return new BadRequestException(
        "Invalid invitation", "This invitation link is invalid, expired or already used.");
  }
}
