package dev.ocaina.tendwise.team;

import static dev.ocaina.tendwise.panel.PanelRequests.tokenFor;
import static dev.ocaina.tendwise.panel.PanelRequests.withSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ocaina.tendwise.TestcontainersConfiguration;
import dev.ocaina.tendwise.auth.AccessTokens;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.user.MembershipId;
import dev.ocaina.tendwise.user.MembershipRepository;
import dev.ocaina.tendwise.user.MembershipRole;
import dev.ocaina.tendwise.user.User;
import dev.ocaina.tendwise.user.UserRepository;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The team API (T34): list members, change a role, remove a member, and the last-owner rule. */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TeamApiTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokens accessTokens;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private BusinessRepository businesses;
  @Autowired private UserRepository users;
  @Autowired private MembershipRepository memberships;
  @Autowired private PasswordEncoder passwordEncoder;

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

  @Test
  void ownerListsTheMembersByName() throws Exception {
    User agent = data.member(slug, "Alex", MembershipRole.AGENT);

    mockMvc
        .perform(withSession(get("/api/v1/panel/{slug}/team", slug), ownerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].userId").value(agent.getId()))
        .andExpect(jsonPath("$[0].name").value("Alex"))
        .andExpect(jsonPath("$[0].email").value(agent.getEmail()))
        .andExpect(jsonPath("$[0].role").value("AGENT"))
        .andExpect(jsonPath("$[0].joinedAt").exists())
        .andExpect(jsonPath("$[1].name").value("Olivia"));
  }

  @Test
  void anAgentCannotUseTheTeamApi() throws Exception {
    User agent = data.member(slug, "Alex", MembershipRole.AGENT);
    String agentToken = tokenFor(accessTokens, agent.getId(), slug, MembershipRole.AGENT);

    mockMvc
        .perform(withSession(get("/api/v1/panel/{slug}/team", slug), agentToken))
        .andExpect(status().isForbidden());
    changeRole(agentToken, agent.getId(), "OWNER").andExpect(status().isForbidden());
    remove(agentToken, owner.getId()).andExpect(status().isForbidden());
  }

  @Test
  void ownerChangesARole() throws Exception {
    User agent = data.member(slug, "Alex", MembershipRole.AGENT);

    changeRole(ownerToken, agent.getId(), "OWNER")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(agent.getId()))
        .andExpect(jsonPath("$.role").value("OWNER"));

    assertThat(roleOf(agent)).isEqualTo(MembershipRole.OWNER);
  }

  @Test
  void anInvalidRoleGets400() throws Exception {
    User agent = data.member(slug, "Alex", MembershipRole.AGENT);

    changeRole(ownerToken, agent.getId(), "ADMIN").andExpect(status().isBadRequest());
  }

  @Test
  void theLastOwnerCannotBeDemotedOrRemoved() throws Exception {
    data.member(slug, "Alex", MembershipRole.AGENT);

    changeRole(ownerToken, owner.getId(), "AGENT")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Last owner"));
    remove(ownerToken, owner.getId()).andExpect(status().isConflict());

    assertThat(roleOf(owner)).isEqualTo(MembershipRole.OWNER);
  }

  @Test
  void anOwnerCanStepDownWhenAnotherOwnerExists() throws Exception {
    data.member(slug, "Bruno", MembershipRole.OWNER);

    changeRole(ownerToken, owner.getId(), "AGENT").andExpect(status().isOk());

    assertThat(roleOf(owner)).isEqualTo(MembershipRole.AGENT);
  }

  @Test
  void twoOwnersDemotingEachOtherAtOnceLeaveOneOwner() throws Exception {
    User second = data.member(slug, "Bruno", MembershipRole.OWNER);
    String secondToken = tokenFor(accessTokens, second.getId(), slug, MembershipRole.OWNER);

    CompletableFuture<Integer> first =
        CompletableFuture.supplyAsync(() -> statusOf(changeRoleUnchecked(ownerToken, second)));
    CompletableFuture<Integer> other =
        CompletableFuture.supplyAsync(() -> statusOf(changeRoleUnchecked(secondToken, owner)));

    assertThat(java.util.List.of(first.get(), other.get())).containsExactlyInAnyOrder(200, 409);
    long owners =
        memberships.findByIdBusinessId(data.businessId(slug)).stream()
            .filter(m -> m.getRole() == MembershipRole.OWNER)
            .count();
    assertThat(owners).isEqualTo(1);
  }

  @Test
  void ownerRemovesAMember() throws Exception {
    User agent = data.member(slug, "Alex", MembershipRole.AGENT);

    remove(ownerToken, agent.getId()).andExpect(status().isNoContent());

    assertThat(memberships.findById(new MembershipId(agent.getId(), data.businessId(slug))))
        .isEmpty();
    // The user account stays: they may belong to other businesses.
    assertThat(users.findById(agent.getId())).isPresent();
  }

  @Test
  void aMemberOfAnotherBusinessGets404() throws Exception {
    String otherSlug = data.business();
    User stranger = data.member(otherSlug, "Sam", MembershipRole.AGENT);

    changeRole(ownerToken, stranger.getId(), "OWNER").andExpect(status().isNotFound());
    remove(ownerToken, stranger.getId()).andExpect(status().isNotFound());

    assertThat(memberships.findById(new MembershipId(stranger.getId(), data.businessId(otherSlug))))
        .hasValueSatisfying(m -> assertThat(m.getRole()).isEqualTo(MembershipRole.AGENT));
  }

  // --- helpers ---

  private ResultActions changeRole(String token, long userId, String role) throws Exception {
    return mockMvc.perform(
        withSession(patch("/api/v1/panel/{slug}/team/{userId}", slug, userId), token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"role\": \"" + role + "\"}"));
  }

  private ResultActions remove(String token, long userId) throws Exception {
    return mockMvc.perform(
        withSession(delete("/api/v1/panel/{slug}/team/{userId}", slug, userId), token));
  }

  private ResultActions changeRoleUnchecked(String token, User target) {
    try {
      return changeRole(token, target.getId(), "AGENT");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static int statusOf(ResultActions result) {
    return result.andReturn().getResponse().getStatus();
  }

  private MembershipRole roleOf(User user) {
    return memberships
        .findById(new MembershipId(user.getId(), data.businessId(slug)))
        .orElseThrow()
        .getRole();
  }
}
