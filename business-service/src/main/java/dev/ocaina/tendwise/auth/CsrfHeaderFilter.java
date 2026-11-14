package dev.ocaina.tendwise.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF check by double submit (ADR 0003): a state-changing request on a cookie-authenticated route
 * must send an X-CSRF-Token header equal to the tendwise_csrf cookie, or it gets 403.
 *
 * <p>Why it works: the browser adds cookies to a forged cross-site request by itself, but only
 * JavaScript running on our own site can read the CSRF cookie and copy it into a header.
 *
 * <p>Spring Security's own CSRF filter is turned off in SecurityConfig: this one is a few lines,
 * and agent-service runs the same check (T35).
 */
class CsrfHeaderFilter extends OncePerRequestFilter {

  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

  /** Routes that use the session cookies. T32 adds /api/v1/panel/ and /api/v1/admin/. */
  private static final List<String> PROTECTED_PREFIXES = List.of("/auth/");

  /** Called before there is a session (and so before there is a CSRF cookie). */
  private static final Set<String> EXEMPT_PATHS = Set.of("/auth/login");

  private final ObjectMapper objectMapper;

  CsrfHeaderFilter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return SAFE_METHODS.contains(request.getMethod())
        || EXEMPT_PATHS.contains(path)
        || PROTECTED_PREFIXES.stream().noneMatch(path::startsWith);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String cookie = AuthCookies.read(request, AuthCookies.CSRF).orElse(null);
    String header = request.getHeader(AuthCookies.CSRF_HEADER);
    if (cookie != null && header != null && sameValue(cookie, header)) {
      chain.doFilter(request, response);
      return;
    }
    ProblemResponses.write(
        request, response, objectMapper, HttpStatus.FORBIDDEN, "Missing or invalid CSRF token.");
  }

  /** Constant-time comparison, like the API key check. */
  private static boolean sameValue(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
