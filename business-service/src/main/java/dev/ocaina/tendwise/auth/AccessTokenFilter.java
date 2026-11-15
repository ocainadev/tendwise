package dev.ocaina.tendwise.auth;

import dev.ocaina.tendwise.user.PlatformRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Logs the request in when it carries a valid access token cookie. Without one, the request goes on
 * anonymous, and routes that need a user answer 401 (see SecurityConfig).
 *
 * <p>Created in SecurityConfig, not a {@code @Component}: Spring Boot would otherwise also register
 * it as a second, plain servlet filter.
 */
class AccessTokenFilter extends OncePerRequestFilter {

  private final AccessTokens accessTokens;

  AccessTokenFilter(AccessTokens accessTokens) {
    this.accessTokens = accessTokens;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    AuthCookies.read(request, AuthCookies.ACCESS)
        .flatMap(accessTokens::verify)
        .ifPresent(user -> {
          SecurityContext context = SecurityContextHolder.createEmptyContext();
          context.setAuthentication(
              UsernamePasswordAuthenticationToken.authenticated(user, null, authorities(user)));
          SecurityContextHolder.setContext(context);
        });
    chain.doFilter(request, response);
  }

  /**
   * ROLE_ADMIN for the platform operator, so SecurityConfig can use hasRole("ADMIN") on the admin
   * routes. Business roles are not authorities: they depend on the business in the URL, which
   * PanelGuard checks.
   */
  private static List<GrantedAuthority> authorities(AuthenticatedUser user) {
    if (user.platformRole() == PlatformRole.ADMIN) {
      return List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
    return List.of();
  }
}
