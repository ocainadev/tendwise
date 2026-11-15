package dev.ocaina.tendwise.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects every request to a service route without the right X-Api-Key header with 401. The service
 * routes are the ones agent-service calls ({@link #SERVICE_ROUTES}). No other route looks at this
 * header: the panel and admin routes use the session cookie, and a cookie never opens a service
 * route.
 *
 * <p>A single servlet filter instead of Spring Security: there is one shared key and no users, so
 * this is all the security these routes need.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Api-Key";

  /**
   * The service routes (docs/architecture.md). SecurityConfig opens exactly these, so the list
   * lives in one place.
   */
  public static final RequestMatcher SERVICE_ROUTES =
      new OrRequestMatcher(
          PathPatternRequestMatcher.withDefaults().matcher("/api/v1/businesses/**"),
          PathPatternRequestMatcher.withDefaults().matcher("/api/v1/orders/**"),
          PathPatternRequestMatcher.withDefaults().matcher("/api/v1/handoffs/**"));

  private final byte[] expectedKey;
  private final ObjectMapper objectMapper;

  public ApiKeyFilter(@Value("${tendwise.api-key}") String apiKey, ObjectMapper objectMapper) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("BUSINESS_SERVICE_API_KEY must be set");
    }
    this.expectedKey = apiKey.getBytes(StandardCharsets.UTF_8);
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !SERVICE_ROUTES.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String key = request.getHeader(HEADER);
    if (key != null && isExpectedKey(key)) {
      chain.doFilter(request, response);
      return;
    }

    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED, "Missing or invalid " + HEADER + " header.");
    problem.setTitle("Unauthorized");
    problem.setInstance(URI.create(request.getRequestURI()));

    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getOutputStream(), problem);
  }

  /** Constant-time comparison, so response time does not reveal how much of the key matched. */
  private boolean isExpectedKey(String key) {
    return MessageDigest.isEqual(expectedKey, key.getBytes(StandardCharsets.UTF_8));
  }
}
