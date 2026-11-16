package dev.ocaina.tendwise.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ocaina.tendwise.publicapi.PublicApiCors;
import dev.ocaina.tendwise.shared.ApiKeyFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security setup for the session cookies (ADR 0003).
 *
 * <p>Each family of routes has one kind of credential (docs/architecture.md):
 *
 * <ul>
 *   <li>Panel and admin routes: the access token cookie. A panel route then goes through PanelGuard
 *       (business slug and role) before its controller.
 *   <li>Service routes (the agent's tools): only the X-Api-Key header, checked by ApiKeyFilter,
 *       which runs after this chain.
 * </ul>
 */
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, AccessTokens accessTokens, ObjectMapper objectMapper) throws Exception {
    http
        // No server-side session: who the user is comes from the access token on every request.
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Spring's CSRF filter is replaced by CsrfHeaderFilter (double submit).
        .csrf(csrf -> csrf.disable())
        // Login is our own JSON endpoint, not Spring's form, basic or logout pages.
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable())
        .logout(logout -> logout.disable())
        .requestCache(cache -> cache.disable())
        // Only /public/v1/** has CORS (the website chat widget); see PublicApiCors.
        .cors(cors -> cors.configurationSource(PublicApiCors.source()))
        .addFilterBefore(
            new CsrfHeaderFilter(objectMapper), UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(
            new AccessTokenFilter(accessTokens), UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(routes ->
            routes
                // An SSE stream (T40) ends with an internal ASYNC dispatch of the same request. The
                // request was already checked when it came in, so the dispatch is let through.
                .dispatcherTypeMatchers(DispatcherType.ASYNC)
                .permitAll()
                .requestMatchers("/auth/me")
                .authenticated()
                // Login, refresh, logout and the JWKS check their own input.
                .requestMatchers("/auth/**")
                .permitAll()
                // The website chat widget (T38): publishable key, then the conversation token.
                // No cookies, so no CSRF check (CsrfHeaderFilter does not cover /public/).
                .requestMatchers("/public/v1/**")
                .permitAll()
                // Business staff. PanelGuard then checks the business slug and the role.
                .requestMatchers("/api/v1/panel/**")
                .authenticated()
                // The platform operator only: 401 without a session, 403 for anyone else.
                .requestMatchers("/api/v1/admin/**")
                .hasRole("ADMIN")
                // Checked by ApiKeyFilter (X-Api-Key). Any other /api/ path is denied below.
                .requestMatchers(ApiKeyFilter.SERVICE_ROUTES)
                .permitAll()
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                .permitAll()
                .requestMatchers("/error")
                .permitAll()
                // Any new route must be opened here on purpose.
                .anyRequest()
                .denyAll())
        .exceptionHandling(errors ->
            errors
                .authenticationEntryPoint((request, response, exception) ->
                    ProblemResponses.write(
                        request, response, objectMapper, HttpStatus.UNAUTHORIZED, "Not logged in."))
                .accessDeniedHandler((request, response, exception) ->
                    ProblemResponses.write(
                        request, response, objectMapper, HttpStatus.FORBIDDEN, "Access denied.")));
    return http.build();
  }
}
