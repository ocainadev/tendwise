package dev.ocaina.tendwise.publicapi;

import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS for /public/v1/** (used by SecurityConfig). Every origin passes the browser's CORS step; the
 * real check is per business, in {@link PublicConversationService}: a request from an origin that
 * is not in the business's allowedDomains gets a 403 problem.
 *
 * <p>Why not list the origins here: before the request body or token is read, the business is not
 * known. Opening CORS is safe because these routes use no cookies (no credentials): a foreign site
 * can only send what it could send with curl, and it gets a 403 without data.
 */
public final class PublicApiCors {

  private PublicApiCors() {}

  public static CorsConfigurationSource source() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of(CorsConfiguration.ALL));
    config.setAllowedMethods(List.of("GET", "POST"));
    // Last-Event-ID: the widget's live stream (T40) sends it when it reconnects.
    config.setAllowedHeaders(
        List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "Last-Event-ID"));
    config.setAllowCredentials(false);
    config.setMaxAge(Duration.ofMinutes(10));

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/public/v1/**", config);
    return source;
  }
}
