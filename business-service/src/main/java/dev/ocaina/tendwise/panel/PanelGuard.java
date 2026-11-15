package dev.ocaina.tendwise.panel;

import dev.ocaina.tendwise.auth.AuthenticatedUser;
import dev.ocaina.tendwise.auth.AuthenticatedUser.TokenMembership;
import dev.ocaina.tendwise.business.Business;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.shared.NotFoundException;
import dev.ocaina.tendwise.user.MembershipRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runs before every controller under {@code /api/v1/panel/{slug}/**} (registered in {@link
 * PanelWebConfig}). Spring Security has already checked that the user is logged in.
 *
 * <ol>
 *   <li>The slug must be one of the businesses in the user's access token. If not, or if the
 *       business no longer exists, the answer is 404: a user never learns whether another business
 *       exists.
 *   <li>A route marked {@link OwnerOnly} needs the OWNER role: an AGENT gets 403.
 *   <li>The business id and role are stored on the request as a {@link CurrentMembership}.
 * </ol>
 */
@Component
class PanelGuard implements HandlerInterceptor {

  static final String PREFIX = "/api/v1/panel/";

  /** Request attribute that holds the {@link CurrentMembership}. */
  static final String ATTRIBUTE = CurrentMembership.class.getName();

  private final BusinessRepository businesses;

  PanelGuard(BusinessRepository businesses) {
    this.businesses = businesses;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    // No controller matched this path: let Spring answer its usual 404.
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }

    String slug = slugFrom(request.getRequestURI());
    AuthenticatedUser user =
        (AuthenticatedUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    TokenMembership membership =
        user.memberships().stream()
            .filter(m -> m.slug().equals(slug))
            .findFirst()
            .orElseThrow(PanelGuard::businessNotFound);
    Business business = businesses.findBySlug(slug).orElseThrow(PanelGuard::businessNotFound);

    if (isOwnerOnly(method) && membership.role() != MembershipRole.OWNER) {
      // Not handled by any controller advice, so Spring Security's access denied handler
      // answers it with the same 403 problem as the admin routes (see SecurityConfig).
      throw new AccessDeniedException("Only the business owner can do this.");
    }

    request.setAttribute(
        ATTRIBUTE, new CurrentMembership(business.getId(), slug, membership.role()));
    return true;
  }

  /** "/api/v1/panel/joes-pizza/conversations" gives "joes-pizza". */
  static String slugFrom(String path) {
    if (!path.startsWith(PREFIX)) {
      return "";
    }
    String rest = path.substring(PREFIX.length());
    int slash = rest.indexOf('/');
    return slash < 0 ? rest : rest.substring(0, slash);
  }

  private static boolean isOwnerOnly(HandlerMethod method) {
    return method.hasMethodAnnotation(OwnerOnly.class)
        || method.getBeanType().isAnnotationPresent(OwnerOnly.class);
  }

  /** The same answer for "not your business" and "no such business". */
  private static NotFoundException businessNotFound() {
    return new NotFoundException("Business not found", "No business with this slug.");
  }
}
