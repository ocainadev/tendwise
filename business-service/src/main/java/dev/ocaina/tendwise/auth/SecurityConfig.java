package dev.ocaina.tendwise.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p>The service routes (/api/**) are left to ApiKeyFilter, which runs after this chain, exactly as
 * before. T32 narrows that filter and adds the panel and admin routes here.
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
        .addFilterBefore(
            new CsrfHeaderFilter(objectMapper), UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(
            new AccessTokenFilter(accessTokens), UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(routes ->
            routes
                .requestMatchers("/auth/me")
                .authenticated()
                // Login, refresh, logout and the JWKS check their own input.
                .requestMatchers("/auth/**")
                .permitAll()
                // Checked by ApiKeyFilter (X-Api-Key).
                .requestMatchers("/api/**")
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
