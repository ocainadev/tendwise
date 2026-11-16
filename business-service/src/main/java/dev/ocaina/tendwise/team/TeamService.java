package dev.ocaina.tendwise.team;

import dev.ocaina.tendwise.shared.ConflictException;
import dev.ocaina.tendwise.shared.NotFoundException;
import dev.ocaina.tendwise.user.Membership;
import dev.ocaina.tendwise.user.MembershipId;
import dev.ocaina.tendwise.user.MembershipRepository;
import dev.ocaina.tendwise.user.MembershipRole;
import dev.ocaina.tendwise.user.User;
import dev.ocaina.tendwise.user.UserRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The business's team (T34): list members, change a role, remove a member. A business always keeps
 * at least one OWNER.
 *
 * <p>Roles live in the access token, so a change reaches the user on their next token refresh,
 * within 15 minutes. Their refresh tokens are not revoked: those belong to the user, not to one
 * business (no business_id), and revoking them would also log the user out of their other
 * businesses. Each refresh reads the memberships again, so a removed member loses this business
 * when their current access token expires.
 */
@Service
public class TeamService {

  private static final Logger log = LoggerFactory.getLogger(TeamService.class);

  private final MembershipRepository memberships;
  private final UserRepository users;

  public TeamService(MembershipRepository memberships, UserRepository users) {
    this.memberships = memberships;
    this.users = users;
  }

  /** Members ordered by name. */
  @Transactional(readOnly = true)
  public List<MemberResponse> list(long businessId) {
    List<Membership> list = memberships.findByIdBusinessId(businessId);
    Map<Long, User> userById =
        users.findAllById(list.stream().map(Membership::getUserId).toList()).stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
    return list.stream()
        .map(membership -> {
          User user = userById.get(membership.getUserId());
          return new MemberResponse(
              user.getId(),
              user.getName(),
              user.getEmail(),
              membership.getRole(),
              membership.getCreatedAt());
        })
        .sorted(Comparator.comparing(MemberResponse::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  @Transactional
  public MemberResponse changeRole(long businessId, long userId, MembershipRole newRole) {
    List<Membership> owners = lockOwners(businessId);
    Membership membership = find(businessId, userId);
    if (newRole == MembershipRole.AGENT && isLastOwner(membership, owners)) {
      throw lastOwner();
    }
    membership.changeRole(newRole);
    log.info("User {} is now {} in business {}", userId, newRole, businessId);

    User user = users.findById(userId).orElseThrow();
    return new MemberResponse(
        user.getId(), user.getName(), user.getEmail(), newRole, membership.getCreatedAt());
  }

  @Transactional
  public void remove(long businessId, long userId) {
    List<Membership> owners = lockOwners(businessId);
    Membership membership = find(businessId, userId);
    if (isLastOwner(membership, owners)) {
      throw lastOwner();
    }
    memberships.delete(membership);
    log.info("User {} removed from business {}", userId, businessId);
  }

  /** Taken before any change, so changes to one business's owners run one at a time. */
  private List<Membership> lockOwners(long businessId) {
    return memberships.findForUpdateByIdBusinessIdAndRole(businessId, MembershipRole.OWNER);
  }

  private Membership find(long businessId, long userId) {
    return memberships
        .findById(new MembershipId(userId, businessId))
        .orElseThrow(() -> new NotFoundException("Member not found", "No such team member."));
  }

  private static boolean isLastOwner(Membership membership, List<Membership> owners) {
    return membership.getRole() == MembershipRole.OWNER && owners.size() <= 1;
  }

  private static ConflictException lastOwner() {
    return new ConflictException(
        "Last owner", "A business needs at least one owner. Make someone else an owner first.");
  }
}
