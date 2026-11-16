package dev.ocaina.tendwise.team;

import static dev.ocaina.tendwise.panel.PanelRequests.tokenFor;
import static dev.ocaina.tendwise.panel.PanelRequests.withSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ocaina.tendwise.TestcontainersConfiguration;
import dev.ocaina.tendwise.auth.AccessTokens;
import dev.ocaina.tendwise.auth.SecureTokens;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.email.EmailSender;
import dev.ocaina.tendwise.email.InvitationEmail;
import dev.ocaina.tendwise.user.MembershipId;
import dev.ocaina.tendwise.user.MembershipRepository;
import dev.ocaina.tendwise.user.MembershipRole;
import dev.ocaina.tendwise.user.User;
import dev.ocaina.tendwise.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Invitations (T34) through the real filter chain and database: create, list, revoke and accept.
 *
 * <p>The email adapter is a spy on the real {@code LogEmailSender}: it still logs, and the test
 * reads the token from the link it was given, as the invitee would from the email.
 */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class InvitationApiTest {

  private static final String INVALID = "This invitation link is invalid, expired or already used.";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AccessTokens accessTokens;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private BusinessRepository businesses;
  @Autowired private UserRepository users;
  @Autowired private MembershipRepository memberships;
  @Autowired private InvitationRepository invitations;
  @Autowired private PasswordEncoder passwordEncoder;
  @MockitoSpyBean private EmailSender emailSender;

  private TeamTestData data;
  private String slug;
  private User owner;
  private String ownerToken;

  @BeforeEach
  void setUp() {
    data = new TeamTestData(jdbc, businesses, users, memberships, passwordEncoder);
    slug = data.business();
    owner = data.member(slug, "Olivia", MembershipRole.OWNER);
    ownerToken = tokenFor(accessTokens, owner.getId(), slug, MembershipRole.OWNER);
  }

  // --- create, list, revoke ---

  @Test
  void ownerCreatesAnInvitationAndTheLinkIsEmailed() throws Exception {
    String email = TeamTestData.uniqueEmail("Joana");

    MockHttpServletResponse response = invite(email.toUpperCase(), "AGENT");

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.get("email").asText()).isEqualTo(email);
    assertThat(body.get("role").asText()).isEqualTo("AGENT");
    Instant expiresAt = Instant.parse(body.get("expiresAt").asText());
    assertThat(expiresAt)
        .isBetween(
            Instant.now().plus(Duration.ofDays(7)).minusSeconds(60),
            Instant.now().plus(Duration.ofDays(7)));

    InvitationEmail sent = lastEmail();
    assertThat(sent.to()).isEqualTo(email);
    assertThat(sent.businessName()).isEqualTo("Shop " + slug);
    assertThat(sent.acceptUrl()).startsWith("http://localhost:5173/invitations/accept#token=");
    String token = tokenOf(sent);
    // The token is never in the response, and only its hash is stored.
    assertThat(response.getContentAsString()).doesNotContain(token);
    Invitation stored = invitations.findById(body.get("id").asLong()).orElseThrow();
    assertThat(stored.getTokenHash()).isEqualTo(SecureTokens.sha256Hex(token));
    assertThat(stored.getInvitedBy()).isEqualTo(owner.getId());

    mockMvc
        .perform(withSession(get("/api/v1/panel/{slug}/invitations", slug), ownerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].email").value(email))
        .andExpect(jsonPath("$[0].token").doesNotExist());
  }

  @Test
  void theEmailIsMaskedAndTheTokenIsNotLogged(CapturedOutput output) throws Exception {
    String email = TeamTestData.uniqueEmail("Joana");

    invite(email, "AGENT");

    String token = tokenOf(lastEmail());
    assertThat(output)
        .contains("invitation email sent to j***@example.test")
        .contains("(link: http://localhost:5173/invitations/accept)")
        .doesNotContain(email)
        .doesNotContain(token);
  }

  @Test
  void anAgentCannotInviteOrListOrRevoke() throws Exception {
    User agent = data.member(slug, "Alex", MembershipRole.AGENT);
    String agentToken = tokenFor(accessTokens, agent.getId(), slug, MembershipRole.AGENT);

    mockMvc
        .perform(
            withSession(post("/api/v1/panel/{slug}/invitations", slug), agentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", "x@example.test", "role", "AGENT"))))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(withSession(get("/api/v1/panel/{slug}/invitations", slug), agentToken))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(withSession(delete("/api/v1/panel/{slug}/invitations/1", slug), agentToken))
        .andExpect(status().isForbidden());
  }

  @Test
  void anInvalidEmailOrRoleGets400() throws Exception {
    assertThat(invite("not-an-email", "AGENT").getStatus()).isEqualTo(400);
    assertThat(invite("x@example.test", "ADMIN").getStatus()).isEqualTo(400);
  }

  @Test
  void invitingSomeoneWhoIsAlreadyAMemberGets409() throws Exception {
    User agent = data.member(slug, "Alex", MembershipRole.AGENT);

    MockHttpServletResponse response = invite(agent.getEmail(), "OWNER");

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(response.getContentAsString()).contains("Already a member");
  }

  @Test
  void invitingTheSameEmailAgainReplacesThePendingInvitation() throws Exception {
    String email = TeamTestData.uniqueEmail("Joana");
    invite(email, "AGENT");
    String firstToken = tokenOf(lastEmail());

    invite(email, "OWNER");
    String secondToken = tokenOf(lastEmail());

    mockMvc
        .perform(withSession(get("/api/v1/panel/{slug}/invitations", slug), ownerToken))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].role").value("OWNER"));
    assertThat(accept(firstToken, "Joana", "a-long-password").getStatus()).isEqualTo(400);
    assertThat(accept(secondToken, "Joana", "a-long-password").getStatus()).isEqualTo(200);
  }

  @Test
  void aRevokedInvitationCannotBeAccepted() throws Exception {
    long id = idOf(invite(TeamTestData.uniqueEmail("Joana"), "AGENT"));
    String token = tokenOf(lastEmail());

    mockMvc
        .perform(withSession(delete("/api/v1/panel/{slug}/invitations/{id}", slug, id), ownerToken))
        .andExpect(status().isNoContent());

    assertInvalid(accept(token, "Joana", "a-long-password"));
    mockMvc
        .perform(withSession(get("/api/v1/panel/{slug}/invitations", slug), ownerToken))
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void anotherBusinessesInvitationCannotBeRevoked() throws Exception {
    long otherInvitation = idOf(invite(TeamTestData.uniqueEmail("Joana"), "AGENT"));
    String otherSlug = data.business();
    User otherOwner = data.member(otherSlug, "Oscar", MembershipRole.OWNER);
    String otherOwnerToken =
        tokenFor(accessTokens, otherOwner.getId(), otherSlug, MembershipRole.OWNER);

    mockMvc
        .perform(
            withSession(
                delete("/api/v1/panel/{slug}/invitations/{id}", otherSlug, otherInvitation),
                otherOwnerToken))
        .andExpect(status().isNotFound());
    assertThat(invitations.findById(otherInvitation)).isPresent();
  }

  // --- accept ---

  @Test
  void aNewUserAcceptsAndCanLogIn() throws Exception {
    String email = TeamTestData.uniqueEmail("Joana");
    invite(email, "AGENT");
    String token = tokenOf(lastEmail());

    // No session cookies and no CSRF header: the invitee is not logged in.
    MockHttpServletResponse response = accept(token, "  Joana  ", "a-long-password");

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.get("slug").asText()).isEqualTo(slug);
    assertThat(body.get("role").asText()).isEqualTo("AGENT");
    assertThat(body.get("accountCreated").asBoolean()).isTrue();
    assertThat(response.getHeaders("Set-Cookie")).isEmpty();

    User created = users.findByEmail(email).orElseThrow();
    assertThat(created.getName()).isEqualTo("Joana");
    assertThat(created.getPlatformRole()).isNull();
    assertThat(passwordEncoder.matches("a-long-password", created.getPasswordHash())).isTrue();

    mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", "a-long-password"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.memberships[0].slug").value(slug))
        .andExpect(jsonPath("$.memberships[0].role").value("AGENT"));
  }

  @Test
  void anExistingUserJustJoinsAndKeepsTheirPassword() throws Exception {
    String otherSlug = data.business();
    User existing = data.member(otherSlug, "Bruno", MembershipRole.OWNER);
    invite(existing.getEmail(), "AGENT");
    String token = tokenOf(lastEmail());

    MockHttpServletResponse response = accept(token, null, null);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(
            objectMapper.readTree(response.getContentAsString()).get("accountCreated").asBoolean())
        .isFalse();
    long businessId = data.businessId(slug);
    assertThat(memberships.findById(new MembershipId(existing.getId(), businessId)))
        .hasValueSatisfying(m -> assertThat(m.getRole()).isEqualTo(MembershipRole.AGENT));
    User after = users.findById(existing.getId()).orElseThrow();
    assertThat(after.getPasswordHash()).isEqualTo(existing.getPasswordHash());
    assertThat(after.getName()).isEqualTo("Bruno");
  }

  @Test
  void anExistingUserIgnoresTheNameAndPasswordSent() throws Exception {
    User existing = data.user("Bruno");
    invite(existing.getEmail(), "OWNER");

    accept(tokenOf(lastEmail()), "Mallory", "attacker-password");

    User after = users.findById(existing.getId()).orElseThrow();
    assertThat(after.getName()).isEqualTo("Bruno");
    assertThat(passwordEncoder.matches(TeamTestData.PASSWORD, after.getPasswordHash())).isTrue();
  }

  @Test
  void aNewUserWithoutNameOrPasswordGets400AndTheInvitationStaysPending() throws Exception {
    invite(TeamTestData.uniqueEmail("Joana"), "AGENT");
    String token = tokenOf(lastEmail());

    MockHttpServletResponse noDetails = accept(token, null, null);
    MockHttpServletResponse shortPassword = accept(token, "Joana", "short");

    assertThat(noDetails.getStatus()).isEqualTo(400);
    assertThat(noDetails.getContentAsString()).contains("Account details required");
    assertThat(shortPassword.getStatus()).isEqualTo(400);
    assertThat(accept(token, "Joana", "a-long-password").getStatus()).isEqualTo(200);
  }

  @Test
  void aUsedTokenCannotBeUsedAgain() throws Exception {
    invite(TeamTestData.uniqueEmail("Joana"), "AGENT");
    String token = tokenOf(lastEmail());
    assertThat(accept(token, "Joana", "a-long-password").getStatus()).isEqualTo(200);

    assertInvalid(accept(token, "Joana", "a-long-password"));
  }

  @Test
  void anExpiredTokenIsRejected() throws Exception {
    String token = SecureTokens.generate();
    invitations.save(
        new Invitation(
            data.businessId(slug),
            TeamTestData.uniqueEmail("Joana"),
            MembershipRole.AGENT,
            SecureTokens.sha256Hex(token),
            owner.getId(),
            Instant.now().minusSeconds(1)));

    assertInvalid(accept(token, "Joana", "a-long-password"));
  }

  @Test
  void anUnknownTokenGetsTheSameAnswer() throws Exception {
    assertInvalid(accept(SecureTokens.generate(), "Joana", "a-long-password"));
  }

  @Test
  void aMissingTokenGets400() throws Exception {
    assertThat(accept(null, "Joana", "a-long-password").getStatus()).isEqualTo(400);
  }

  // --- helpers ---

  private MockHttpServletResponse invite(String email, String role) throws Exception {
    return mockMvc
        .perform(
            withSession(post("/api/v1/panel/{slug}/invitations", slug), ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "role", role))))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse accept(String token, String name, String password)
      throws Exception {
    Map<String, String> body = new java.util.HashMap<>();
    body.put("token", token);
    body.put("name", name);
    body.put("password", password);
    return mockMvc
        .perform(
            post("/auth/invitations/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)))
        .andReturn()
        .getResponse();
  }

  private void assertInvalid(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(400);
    JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.get("title").asText()).isEqualTo("Invalid invitation");
    assertThat(body.get("detail").asText()).isEqualTo(INVALID);
  }

  private InvitationEmail lastEmail() {
    ArgumentCaptor<InvitationEmail> captor = ArgumentCaptor.forClass(InvitationEmail.class);
    verify(emailSender, org.mockito.Mockito.atLeastOnce()).sendInvitation(captor.capture());
    return captor.getValue();
  }

  private static String tokenOf(InvitationEmail email) {
    return email.acceptUrl().substring(email.acceptUrl().indexOf("#token=") + "#token=".length());
  }

  private long idOf(MockHttpServletResponse response) throws Exception {
    return objectMapper.readTree(response.getContentAsString()).get("id").asLong();
  }

  private String json(Object value) throws Exception {
    return objectMapper.writeValueAsString(value);
  }
}
