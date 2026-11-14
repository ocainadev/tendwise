package dev.ocaina.tendwise.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The three session cookies (ADR 0003). All are SameSite=Lax and Secure (unless
 * AUTH_COOKIE_SECURE=false, for plain http on a local machine).
 *
 * <ul>
 *   <li>{@value #ACCESS}: the JWT access token. HttpOnly, path /, 15 minutes.
 *   <li>{@value #REFRESH}: the refresh token. HttpOnly, path /auth (only the auth routes ever
 *       receive it), 30 days.
 *   <li>{@value #CSRF}: the CSRF token. Not HttpOnly, so the web app can copy it into the {@value
 *       #CSRF_HEADER} header. Path /, 30 days, like the refresh token it protects.
 * </ul>
 */
@Component
public class AuthCookies {

  public static final String ACCESS = "tendwise_access";
  public static final String REFRESH = "tendwise_refresh";
  public static final String CSRF = "tendwise_csrf";
  public static final String CSRF_HEADER = "X-CSRF-Token";

  static final String REFRESH_PATH = "/auth";

  private final boolean secure;

  public AuthCookies(@Value("${tendwise.auth.cookie-secure}") boolean secure) {
    this.secure = secure;
  }

  /** Set-Cookie values that start (or renew) a session. */
  List<String> forSession(String accessToken, String refreshToken, String csrfToken) {
    return List.of(
        cookie(ACCESS, accessToken, "/", true, AccessTokens.LIFETIME),
        cookie(REFRESH, refreshToken, REFRESH_PATH, true, AuthService.REFRESH_LIFETIME),
        cookie(CSRF, csrfToken, "/", false, AuthService.REFRESH_LIFETIME));
  }

  /** Set-Cookie values that delete the three cookies (same name and path, Max-Age=0). */
  List<String> cleared() {
    return List.of(
        cookie(ACCESS, "", "/", true, Duration.ZERO),
        cookie(REFRESH, "", REFRESH_PATH, true, Duration.ZERO),
        cookie(CSRF, "", "/", false, Duration.ZERO));
  }

  static Optional<String> read(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    return Arrays.stream(cookies)
        .filter(cookie -> cookie.getName().equals(name))
        .map(Cookie::getValue)
        .filter(value -> !value.isEmpty())
        .findFirst();
  }

  private String cookie(String name, String value, String path, boolean httpOnly, Duration maxAge) {
    return ResponseCookie.from(name, value)
        .httpOnly(httpOnly)
        .secure(secure)
        .sameSite("Lax")
        .path(path)
        .maxAge(maxAge)
        .build()
        .toString();
  }
}
