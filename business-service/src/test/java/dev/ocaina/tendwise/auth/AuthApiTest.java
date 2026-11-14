package dev.ocaina.tendwise.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ocaina.tendwise.TestcontainersConfiguration;
import dev.ocaina.tendwise.auth.AuthenticatedUser.TokenMembership;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.user.Membership;
import dev.ocaina.tendwise.user.MembershipRepository;
import dev.ocaina.tendwise.user.MembershipRole;
import dev.ocaina.tendwise.user.RefreshToken;
import dev.ocaina.tendwise.user.RefreshTokenRepository;
import dev.ocaina.tendwise.user.User;
import dev.ocaina.tendwise.user.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Login, refresh, logout, /auth/me, CSRF and the JWKS, through the real filter chain and database.
 *
 * <p>Not {@code @Transactional}: every request commits on its own, as in production. Each test
 * creates its own user with a unique email, so tests never share state.
 */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthApiTest {

  private static final String PASSWORD = "correct-horse-battery-staple";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository users;
  @Autowired private MembershipRepository memberships;
  @Autowired private BusinessRepository businesses;
  @Autowired private RefreshTokenRepository refreshTokens;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private AccessTokens accessTokens;
  @Autowired private JdbcTemplate jdbc;

  // --- login ---

  @Test
  void loginSetsTheThreeCookiesAndReturnsTheUser() throws Exception {
    User user = createOwnerOfJoesPizza();

    MockHttpServletResponse response = login(user.getEmail(), PASSWORD);

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.at("/user/id").asLong()).isEqualTo(user.getId());
    assertThat(body.at("/user/email").asText()).isEqualTo(user.getEmail());
    assertThat(body.at("/user/name").asText()).isEqualTo("Ana");
    assertThat(body.get("platformRole").isNull()).isTrue();
    assertThat(body.at("/memberships/0/slug").asText()).isEqualTo("joes-pizza");
    assertThat(body.at("/memberships/0/name").asText()).isEqualTo("Joe's Pizza");
    assertThat(body.at("/memberships/0/role").asText()).isEqualTo("OWNER");

    String access = setCookie(response, AuthCookies.ACCESS);
    String refresh = setCookie(response, AuthCookies.REFRESH);
    String csrf = setCookie(response, AuthCookies.CSRF);
    assertThat(access).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/;", "Max-Age=900");
    assertThat(refresh).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/auth;");
    assertThat(refresh).contains("Max-Age=" + Duration.ofDays(30).toSeconds());
    // The web app must be able to read the CSRF cookie to copy it into the header.
    assertThat(csrf).contains("Secure", "SameSite=Lax", "Path=/;").doesNotContain("HttpOnly");

    // Tokens travel only in cookies.
    assertThat(response.getContentAsString())
        .doesNotContain(cookie(response, AuthCookies.ACCESS).getValue())
        .doesNotContain(cookie(response, AuthCookies.REFRESH).getValue());

    User saved = users.findById(user.getId()).orElseThrow();
    assertThat(saved.getLastLoginAt()).isNotNull();
  }

  @Test
  void emailIsMatchedIgnoringCase() throws Exception {
    User user = createOwnerOfJoesPizza();

    assertThat(login(user.getEmail().toUpperCase(), PASSWORD).getStatus()).isEqualTo(200);
  }

  @Test
  void wrongPasswordAndUnknownEmailGetTheSameAnswer() throws Exception {
    User user = createOwnerOfJoesPizza();

    MockHttpServletResponse wrongPassword = login(user.getEmail(), "wrong-password");
    MockHttpServletResponse unknownEmail =
        login("nobody-" + UUID.randomUUID() + "@x.test", PASSWORD);

    assertThat(wrongPassword.getStatus()).isEqualTo(401);
    assertThat(unknownEmail.getStatus()).isEqualTo(401);
    assertThat(wrongPassword.getContentAsString()).isEqualTo(unknownEmail.getContentAsString());
    assertThat(objectMapper.readTree(wrongPassword.getContentAsString()).get("detail").asText())
        .isEqualTo("Invalid email or password.");
    assertThat(users.findById(user.getId()).orElseThrow().getFailedLoginCount()).isEqualTo(1);
  }

  @Test
  void fiveFailedLoginsLockTheAccountForFifteenMinutes() throws Exception {
    User user = createOwnerOfJoesPizza();
    for (int i = 0; i < 5; i++) {
      assertThat(login(user.getEmail(), "wrong-password").getStatus()).isEqualTo(401);
    }

    User locked = users.findById(user.getId()).orElseThrow();
    assertThat(locked.getLockedUntil())
        .isBetween(
            Instant.now().plus(Duration.ofMinutes(14)), Instant.now().plus(Duration.ofMinutes(16)));

    // Even the right password is refused, with the same answer as a wrong one.
    MockHttpServletResponse whileLocked = login(user.getEmail(), PASSWORD);
    assertThat(whileLocked.getStatus()).isEqualTo(401);
    assertThat(whileLocked.getContentAsString())
        .isEqualTo(login("nobody-" + UUID.randomUUID() + "@x.test", PASSWORD).getContentAsString());

    // Once the lock is over, the right password works and clears it.
    jdbc.update(
        "UPDATE core.users SET locked_until = now() - interval '1 second' WHERE id = ?",
        user.getId());
    assertThat(login(user.getEmail(), PASSWORD).getStatus()).isEqualTo(200);
    User unlocked = users.findById(user.getId()).orElseThrow();
    assertThat(unlocked.getLockedUntil()).isNull();
    assertThat(unlocked.getFailedLoginCount()).isZero();
  }

  @Test
  void aSuccessfulLoginResetsTheFailedCount() throws Exception {
    User user = createOwnerOfJoesPizza();
    for (int i = 0; i < 4; i++) {
      login(user.getEmail(), "wrong-password");
    }

    assertThat(login(user.getEmail(), PASSWORD).getStatus()).isEqualTo(200);
    assertThat(users.findById(user.getId()).orElseThrow().getFailedLoginCount()).isZero();
    // So one more failure does not lock the account.
    login(user.getEmail(), "wrong-password");
    assertThat(users.findById(user.getId()).orElseThrow().getLockedUntil()).isNull();
  }

  @Test
  void aDisabledUserCannotLogIn() throws Exception {
    User user = createOwnerOfJoesPizza();
    jdbc.update("UPDATE core.users SET status = 'DISABLED' WHERE id = ?", user.getId());

    MockHttpServletResponse response = login(user.getEmail(), PASSWORD);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(objectMapper.readTree(response.getContentAsString()).get("detail").asText())
        .isEqualTo("Invalid email or password.");
  }

  @Test
  void loginWithoutABodyIs400() throws Exception {
    mockMvc
        .perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  // --- /auth/me and the access token ---

  @Test
  void meReturnsTheLoggedInUser() throws Exception {
    User user = createOwnerOfJoesPizza();
    Session session = Session.of(login(user.getEmail(), PASSWORD));

    mockMvc
        .perform(get("/auth/me").cookie(session.access()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.id").value(user.getId()))
        .andExpect(jsonPath("$.memberships[0].slug").value("joes-pizza"));
  }

  @Test
  void meWithoutAnAccessTokenIs401() throws Exception {
    mockMvc
        .perform(get("/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Not logged in."));
  }

  @Test
  void anExpiredAccessTokenIsRejected() throws Exception {
    User user = createOwnerOfJoesPizza();
    String expired =
        accessTokens.issue(
            new AuthenticatedUser(user.getId(), null, List.of()),
            Instant.now().minus(Duration.ofMinutes(16)));

    mockMvc
        .perform(get("/auth/me").cookie(new Cookie(AuthCookies.ACCESS, expired)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aTamperedAccessTokenIsRejected() throws Exception {
    User user = createOwnerOfJoesPizza();
    String token = Session.of(login(user.getEmail(), PASSWORD)).access().getValue();
    // Change the payload (claims) part, keep the signature.
    String[] parts = token.split("\\.");
    String otherPayload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ("{\"iss\":\"tendwise-business-service\",\"sub\":\"1\",\"platformRole\":\"ADMIN\","
                        + "\"memberships\":[],\"exp\":9999999999}")
                    .getBytes());
    String tampered = parts[0] + "." + otherPayload + "." + parts[2];

    mockMvc
        .perform(get("/auth/me").cookie(new Cookie(AuthCookies.ACCESS, tampered)))
        .andExpect(status().isUnauthorized());
  }

  // --- refresh ---

  @Test
  void refreshRotatesTheRefreshTokenAndIssuesANewAccessToken() throws Exception {
    User user = createOwnerOfJoesPizza();
    Session first = Session.of(login(user.getEmail(), PASSWORD));

    MockHttpServletResponse response = refresh(first);

    assertThat(response.getStatus()).isEqualTo(200);
    Session second = Session.of(response);
    assertThat(second.refresh().getValue()).isNotEqualTo(first.refresh().getValue());
    // (The access token may be byte-for-byte the same within one second: Ed25519 signatures are
    // deterministic and iat/exp have second precision. It is checked by /auth/me below.)
    assertThat(second.csrf().getValue()).isNotEqualTo(first.csrf().getValue());

    RefreshToken used = tokenFor(first);
    RefreshToken issued = tokenFor(second);
    assertThat(used.getRevokedAt()).isNotNull();
    assertThat(issued.getRevokedAt()).isNull();
    assertThat(issued.getFamilyId()).isEqualTo(used.getFamilyId());

    mockMvc.perform(get("/auth/me").cookie(second.access())).andExpect(status().isOk());
  }

  @Test
  void reusingARefreshTokenRevokesTheWholeFamily() throws Exception {
    User user = createOwnerOfJoesPizza();
    Session first = Session.of(login(user.getEmail(), PASSWORD));
    Session second = Session.of(refresh(first));

    // The old token is used again: someone holds a copy of it.
    MockHttpServletResponse reuse = refresh(first);
    assertThat(reuse.getStatus()).isEqualTo(401);
    assertThat(setCookie(reuse, AuthCookies.REFRESH)).contains("Max-Age=0");

    // The newest token of that login stops working too.
    assertThat(refresh(second).getStatus()).isEqualTo(401);
    assertThat(tokenFor(second).getRevokedAt()).isNotNull();
  }

  @Test
  void anotherLoginIsNotRevokedByAReuse() throws Exception {
    User user = createOwnerOfJoesPizza();
    Session laptop = Session.of(login(user.getEmail(), PASSWORD));
    Session phone = Session.of(login(user.getEmail(), PASSWORD));
    refresh(laptop);

    refresh(laptop);

    assertThat(refresh(phone).getStatus()).isEqualTo(200);
  }

  @Test
  void anExpiredRefreshTokenIsRejected() throws Exception {
    User user = createOwnerOfJoesPizza();
    Session session = Session.of(login(user.getEmail(), PASSWORD));
    jdbc.update(
        "UPDATE core.refresh_tokens SET expires_at = now() - interval '1 second' WHERE id = ?",
        tokenFor(session).getId());

    assertThat(refresh(session).getStatus()).isEqualTo(401);
  }

  @Test
  void anUnknownOrMissingRefreshTokenIsRejected() throws Exception {
    Cookie csrf = new Cookie(AuthCookies.CSRF, "csrf-value");
    MockHttpServletRequestBuilder withCsrf =
        post("/auth/refresh").cookie(csrf).header(AuthCookies.CSRF_HEADER, "csrf-value");

    mockMvc.perform(withCsrf).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/auth/refresh")
                .cookie(csrf, new Cookie(AuthCookies.REFRESH, "not-a-real-token"))
                .header(AuthCookies.CSRF_HEADER, "csrf-value"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Your session has ended. Please log in again."));
  }

  @Test
  void aDisabledUserCannotRefresh() throws Exception {
    User user = createOwnerOfJoesPizza();
    Session session = Session.of(login(user.getEmail(), PASSWORD));
    jdbc.update("UPDATE core.users SET status = 'DISABLED' WHERE id = ?", user.getId());

    assertThat(refresh(session).getStatus()).isEqualTo(401);
    assertThat(tokenFor(session).getRevokedAt()).isNotNull();
  }

  // --- logout ---

  @Test
  void logoutRevokesTheRefreshTokenAndClearsTheCookies() throws Exception {
    User user = createOwnerOfJoesPizza();
    Session session = Session.of(login(user.getEmail(), PASSWORD));

    MockHttpServletResponse response =
        mockMvc
            .perform(
                post("/auth/logout")
                    .cookie(session.refresh(), session.csrf())
                    .header(AuthCookies.CSRF_HEADER, session.csrf().getValue()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(204);
    for (String name : List.of(AuthCookies.ACCESS, AuthCookies.REFRESH, AuthCookies.CSRF)) {
      assertThat(setCookie(response, name)).contains("Max-Age=0");
    }
    assertThat(tokenFor(session).getRevokedAt()).isNotNull();
    assertThat(refresh(session).getStatus()).isEqualTo(401);
  }

  // --- CSRF ---

  @Test
  void refreshWithoutTheCsrfHeaderIs403() throws Exception {
    User user = createOwnerOfJoesPizza();
    Session session = Session.of(login(user.getEmail(), PASSWORD));

    mockMvc
        .perform(post("/auth/refresh").cookie(session.refresh(), session.csrf()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Missing or invalid CSRF token."));

    // The request never reached the controller: the token was not rotated.
    assertThat(tokenFor(session).getRevokedAt()).isNull();
  }

  @Test
  void aCsrfHeaderThatDoesNotMatchTheCookieIs403() throws Exception {
    User user = createOwnerOfJoesPizza();
    Session session = Session.of(login(user.getEmail(), PASSWORD));

    mockMvc
        .perform(
            post("/auth/refresh")
                .cookie(session.refresh(), session.csrf())
                .header(AuthCookies.CSRF_HEADER, "something-else"))
        .andExpect(status().isForbidden());
  }

  @Test
  void logoutWithoutTheCsrfHeaderIs403AndKeepsTheSession() throws Exception {
    User user = createOwnerOfJoesPizza();
    Session session = Session.of(login(user.getEmail(), PASSWORD));

    // A forged cross-site request carries the cookies, but cannot set the header.
    mockMvc
        .perform(post("/auth/logout").cookie(session.refresh(), session.access()))
        .andExpect(status().isForbidden());

    assertThat(tokenFor(session).getRevokedAt()).isNull();
  }

  // --- JWKS ---

  @Test
  void theAccessTokenVerifiesWithTheKeyFromTheJwks() throws Exception {
    User user = createOwnerOfJoesPizza();
    String token = Session.of(login(user.getEmail(), PASSWORD)).access().getValue();

    MockHttpServletResponse response =
        mockMvc.perform(get("/auth/jwks.json")).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("max-age=300");
    JsonNode jwk = objectMapper.readTree(response.getContentAsString()).at("/keys/0");
    assertThat(jwk.get("kty").asText()).isEqualTo("OKP");
    assertThat(jwk.get("crv").asText()).isEqualTo("Ed25519");
    assertThat(jwk.get("alg").asText()).isEqualTo("EdDSA");
    assertThat(jwk.has("d")).as("no private key part").isFalse();

    // Verify the way agent-service will: only with the public key from the JWKS.
    Jws<Claims> verified =
        Jwts.parser().verifyWith(publicKeyFrom(jwk)).build().parseSignedClaims(token);
    assertThat(verified.getHeader().getKeyId()).isEqualTo(jwk.get("kid").asText());
    assertThat(verified.getHeader().getAlgorithm()).isEqualTo("EdDSA");
    Claims claims = verified.getPayload();
    assertThat(claims.getSubject()).isEqualTo(String.valueOf(user.getId()));
    assertThat(claims.getIssuer()).isEqualTo("tendwise-business-service");
    assertThat(claims.get("platformRole")).isNull();
    assertThat(claims.get("memberships", List.class))
        .containsExactly(Map.of("slug", "joes-pizza", "role", "OWNER"));
    assertThat(claims.getExpiration().toInstant())
        .isBetween(
            Instant.now().plus(Duration.ofMinutes(14)), Instant.now().plus(Duration.ofMinutes(16)));
    assertThat(claims).doesNotContainKeys("email", "name");

    assertThat(accessTokens.verify(token).orElseThrow().memberships())
        .containsExactly(new TokenMembership("joes-pizza", MembershipRole.OWNER));
  }

  // --- helpers ---

  /** The three cookies a login or refresh sets. */
  private record Session(Cookie access, Cookie refresh, Cookie csrf) {
    static Session of(MockHttpServletResponse response) {
      return new Session(
          cookie(response, AuthCookies.ACCESS),
          cookie(response, AuthCookies.REFRESH),
          cookie(response, AuthCookies.CSRF));
    }
  }

  private User createOwnerOfJoesPizza() {
    String email = "ana-" + UUID.randomUUID() + "@example.com";
    User user = users.save(new User(email, passwordEncoder.encode(PASSWORD), "Ana", null));
    Long businessId = businesses.findBySlug("joes-pizza").orElseThrow().getId();
    memberships.save(new Membership(user.getId(), businessId, MembershipRole.OWNER));
    return user;
  }

  private MockHttpServletResponse login(String email, String password) throws Exception {
    String body = objectMapper.writeValueAsString(Map.of("email", email, "password", password));
    MvcResult result =
        mockMvc
            .perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn();
    return result.getResponse();
  }

  private MockHttpServletResponse refresh(Session session) throws Exception {
    return mockMvc
        .perform(
            post("/auth/refresh")
                .cookie(session.refresh(), session.csrf())
                .header(AuthCookies.CSRF_HEADER, session.csrf().getValue()))
        .andReturn()
        .getResponse();
  }

  private RefreshToken tokenFor(Session session) {
    return refreshTokens
        .findByTokenHash(SecureTokens.sha256Hex(session.refresh().getValue()))
        .orElseThrow();
  }

  private static Cookie cookie(MockHttpServletResponse response, String name) {
    Cookie cookie = response.getCookie(name);
    assertThat(cookie).as("cookie " + name).isNotNull();
    return cookie;
  }

  private static String setCookie(MockHttpServletResponse response, String name) {
    return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
        .filter(header -> header.startsWith(name + "="))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no Set-Cookie for " + name));
  }

  /** Builds an Ed25519 public key from the JWK's "x" (the raw 32 key bytes). */
  private static PublicKey publicKeyFrom(JsonNode jwk) throws Exception {
    byte[] raw = Base64.getUrlDecoder().decode(jwk.get("x").asText());
    byte[] prefix = HexFormat.of().parseHex("302a300506032b6570032100");
    byte[] der = new byte[prefix.length + raw.length];
    System.arraycopy(prefix, 0, der, 0, prefix.length);
    System.arraycopy(raw, 0, der, prefix.length, raw.length);
    return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
  }
}
