package dev.ocaina.tendwise.auth;

import dev.ocaina.tendwise.auth.AuthenticatedUser.TokenMembership;
import dev.ocaina.tendwise.user.MembershipRole;
import dev.ocaina.tendwise.user.PlatformRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Issues and checks the access token: a JWT signed with Ed25519 (alg EdDSA), valid 15 minutes (ADR
 * 0003). agent-service checks the same token with the public key from {@link #jwks()}.
 *
 * <p>Claims: {@code sub} (user id), {@code platformRole} (only for the operator), {@code
 * memberships} ([{slug, role}]), {@code iss}, {@code iat}, {@code exp}. No email or name: the token
 * holds no personal data.
 */
@Component
public class AccessTokens {

  static final Duration LIFETIME = Duration.ofMinutes(15);
  static final String ISSUER = "tendwise-business-service";

  private final SigningKey key;
  private final JwtParser parser;

  /** Fails at startup, with a clear message, when the key is missing or invalid. */
  public AccessTokens(@Value("${tendwise.auth.jwt-private-key}") String privateKey) {
    this.key = SigningKey.parse(privateKey);
    this.parser = Jwts.parser().verifyWith(key.publicKey()).requireIssuer(ISSUER).build();
  }

  public String issue(AuthenticatedUser user, Instant issuedAt) {
    List<Map<String, String>> memberships =
        user.memberships().stream()
            .map(m -> Map.of("slug", m.slug(), "role", m.role().name()))
            .toList();
    return Jwts.builder()
        .header()
        .keyId(key.keyId())
        .and()
        .issuer(ISSUER)
        .subject(String.valueOf(user.userId()))
        // A null value leaves the claim out.
        .claim("platformRole", user.platformRole() == null ? null : user.platformRole().name())
        .claim("memberships", memberships)
        .issuedAt(Date.from(issuedAt))
        .expiration(Date.from(issuedAt.plus(LIFETIME)))
        .signWith(key.privateKey(), Jwts.SIG.EdDSA)
        .compact();
  }

  /** The user in the token, or empty if the token is expired, tampered with or malformed. */
  public Optional<AuthenticatedUser> verify(String token) {
    try {
      Claims claims = parser.parseSignedClaims(token).getPayload();
      String platformRole = claims.get("platformRole", String.class);
      List<TokenMembership> memberships =
          claims.get("memberships", List.class).stream().map(AccessTokens::toMembership).toList();
      return Optional.of(
          new AuthenticatedUser(
              Long.parseLong(claims.getSubject()),
              platformRole == null ? null : PlatformRole.valueOf(platformRole),
              memberships));
    } catch (JwtException
        | IllegalArgumentException
        | ClassCastException
        | NullPointerException e) {
      // IllegalArgumentException also covers a bad number or enum value.
      return Optional.empty();
    }
  }

  /** The JSON Web Key Set (RFC 7517) with the public key, served at /auth/jwks.json. */
  public Map<String, Object> jwks() {
    Map<String, Object> jwk = new LinkedHashMap<>();
    jwk.put("kty", "OKP");
    jwk.put("crv", "Ed25519");
    jwk.put("x", key.publicKeyX());
    jwk.put("kid", key.keyId());
    jwk.put("alg", "EdDSA");
    jwk.put("use", "sig");
    return Map.of("keys", List.of(jwk));
  }

  private static TokenMembership toMembership(Object item) {
    Map<?, ?> map = (Map<?, ?>) item;
    return new TokenMembership(
        (String) map.get("slug"), MembershipRole.valueOf((String) map.get("role")));
  }
}
