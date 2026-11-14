package dev.ocaina.tendwise.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
          // No authorities yet: roles and the business guard come in T32.
          SecurityContext context = SecurityContextHolder.createEmptyContext();
          context.setAuthentication(
              UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
          SecurityContextHolder.setContext(context);
        });
    chain.doFilter(request, response);
  }
}
