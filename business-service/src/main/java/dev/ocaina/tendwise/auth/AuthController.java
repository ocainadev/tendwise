package dev.ocaina.tendwise.auth;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Panel login (ADR 0003). The tokens travel only in cookies, never in a response body. Every POST
 * except login also needs the X-CSRF-Token header (CsrfHeaderFilter).
 */
@RestController
@RequestMapping("/auth")
@SecurityRequirements // No X-Api-Key here: these routes use the session cookies.
public class AuthController {

  private final AuthService authService;
  private final AccessTokens accessTokens;
  private final AuthCookies cookies;

  public AuthController(AuthService authService, AccessTokens accessTokens, AuthCookies cookies) {
    this.authService = authService;
    this.accessTokens = accessTokens;
    this.cookies = cookies;
  }

  @PostMapping("/login")
  ResponseEntity<MeResponse> login(@Valid @RequestBody LoginRequest body) {
    return withSession(authService.login(body.email(), body.password()));
  }

  @PostMapping("/refresh")
  ResponseEntity<MeResponse> refresh(
      @CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken) {
    if (refreshToken == null || refreshToken.isEmpty()) {
      throw AuthFailedException.sessionEnded();
    }
    return withSession(authService.refresh(refreshToken));
  }

  @PostMapping("/logout")
  ResponseEntity<Void> logout(
      @CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken) {
    authService.logout(refreshToken);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, toArray(cookies.cleared()))
        .build();
  }

  /** Only reached with a valid access token (SecurityConfig); 401 otherwise. */
  @GetMapping("/me")
  MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
    return authService.currentUser(user.userId()).orElseThrow(AuthFailedException::sessionEnded);
  }

  /** Public by design: it holds only the public key. Cached for 5 minutes. */
  @GetMapping("/jwks.json")
  ResponseEntity<Map<String, Object>> jwks() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
        .body(accessTokens.jwks());
  }

  /** A failed login or refresh also deletes the session cookies, so no stale session remains. */
  @ExceptionHandler(AuthFailedException.class)
  ResponseEntity<ProblemDetail> handleAuthFailed(AuthFailedException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.SET_COOKIE, toArray(cookies.cleared()))
        .body(problem);
  }

  private ResponseEntity<MeResponse> withSession(AuthService.Session session) {
    List<String> setCookies =
        cookies.forSession(session.accessToken(), session.refreshToken(), session.csrfToken());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, toArray(setCookies))
        .body(session.me());
  }

  private static String[] toArray(List<String> values) {
    return values.toArray(String[]::new);
  }
}
