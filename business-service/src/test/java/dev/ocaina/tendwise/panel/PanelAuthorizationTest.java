package dev.ocaina.tendwise.panel;

import static dev.ocaina.tendwise.panel.PanelRequests.adminToken;
import static dev.ocaina.tendwise.panel.PanelRequests.tokenFor;
import static dev.ocaina.tendwise.panel.PanelRequests.withSession;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ocaina.tendwise.TestcontainersConfiguration;
import dev.ocaina.tendwise.auth.AccessTokens;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.user.MembershipRole;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The panel guard (slug and role), the admin prefix, and the split between session cookies and the
 * service key, through the real filter chain.
 */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestPanelRoutes.class})
class PanelAuthorizationTest {

  private static final String JOES = "joes-pizza";

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokens accessTokens;
  @Autowired private BusinessRepository businesses;

  // --- panel guard ---

  @Test
  void aMemberReachesTheControllerWithTheBusinessIdAndRole() throws Exception {
    long joesId = businesses.findBySlug(JOES).orElseThrow().getId();
    String token = tokenFor(accessTokens, JOES, MembershipRole.AGENT);

    mockMvc
        .perform(withSession(get(TestPanelRoutes.MEMBER_ROUTE, JOES), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.businessId").value(joesId))
        .andExpect(jsonPath("$.slug").value(JOES))
        .andExpect(jsonPath("$.role").value("AGENT"));
  }

  @Test
  void aMemberOfAnotherBusinessGets404() throws Exception {
    String token = tokenFor(accessTokens, "denim-corner", MembershipRole.OWNER);

    mockMvc
        .perform(withSession(get(TestPanelRoutes.MEMBER_ROUTE, JOES), token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Business not found"));
  }

  @Test
  void anUnknownSlugGets404EvenIfTheTokenNamesIt() throws Exception {
    // A business deleted after the token was issued.
    String token = tokenFor(accessTokens, "no-such-business", MembershipRole.OWNER);

    mockMvc
        .perform(withSession(get(TestPanelRoutes.MEMBER_ROUTE, "no-such-business"), token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Business not found"));
  }

  @Test
  void anAgentGets403OnAnOwnerRoute() throws Exception {
    String token = tokenFor(accessTokens, JOES, MembershipRole.AGENT);

    mockMvc
        .perform(withSession(post(TestPanelRoutes.OWNER_ROUTE, JOES), token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Access denied."));
  }

  @Test
  void anOwnerReachesAnOwnerRoute() throws Exception {
    String token = tokenFor(accessTokens, JOES, MembershipRole.OWNER);

    mockMvc
        .perform(withSession(post(TestPanelRoutes.OWNER_ROUTE, JOES), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("OWNER"));
  }

  @Test
  void aNonMemberGets404NotForbiddenOnAnOwnerRoute() throws Exception {
    String token = tokenFor(accessTokens, "denim-corner", MembershipRole.OWNER);

    mockMvc
        .perform(withSession(post(TestPanelRoutes.OWNER_ROUTE, JOES), token))
        .andExpect(status().isNotFound());
  }

  @Test
  void panelRoutesNeedALogin() throws Exception {
    mockMvc.perform(get(TestPanelRoutes.MEMBER_ROUTE, JOES)).andExpect(status().isUnauthorized());
  }

  @Test
  void panelPostsNeedTheCsrfHeader() throws Exception {
    String token = tokenFor(accessTokens, JOES, MembershipRole.OWNER);

    mockMvc
        .perform(
            post(TestPanelRoutes.MEMBER_ROUTE, JOES).cookie(new Cookie("tendwise_access", token)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Missing or invalid CSRF token."));
  }

  @Test
  void anUnknownPanelRouteGets404() throws Exception {
    String token = tokenFor(accessTokens, JOES, MembershipRole.OWNER);

    mockMvc
        .perform(withSession(get("/api/v1/panel/{slug}/no-such-route", JOES), token))
        .andExpect(status().isNotFound());
  }

  // --- admin prefix ---

  @Test
  void thePlatformAdminReachesAdminRoutes() throws Exception {
    mockMvc
        .perform(withSession(get(TestPanelRoutes.ADMIN_ROUTE), adminToken(accessTokens)))
        .andExpect(status().isOk());
  }

  @Test
  void businessStaffGet403OnAdminRoutes() throws Exception {
    String token = tokenFor(accessTokens, JOES, MembershipRole.OWNER);

    mockMvc
        .perform(withSession(get(TestPanelRoutes.ADMIN_ROUTE), token))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminRoutesNeedALogin() throws Exception {
    mockMvc.perform(get(TestPanelRoutes.ADMIN_ROUTE)).andExpect(status().isUnauthorized());
  }

  // --- service key and cookies never cross over ---

  @Test
  void theServiceKeyDoesNotOpenPanelOrAdminRoutes() throws Exception {
    mockMvc
        .perform(get(TestPanelRoutes.MEMBER_ROUTE, JOES).header("X-Api-Key", "test-key"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get(TestPanelRoutes.ADMIN_ROUTE).header("X-Api-Key", "test-key"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void theServiceKeyDoesNotOpenAuthRoutes() throws Exception {
    mockMvc
        .perform(get("/auth/me").header("X-Api-Key", "test-key"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aSessionCookieDoesNotOpenServiceRoutes() throws Exception {
    String owner = tokenFor(accessTokens, JOES, MembershipRole.OWNER);
    String admin = adminToken(accessTokens);

    mockMvc
        .perform(withSession(get("/api/v1/businesses/{slug}", JOES), owner))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Missing or invalid X-Api-Key header."));
    mockMvc
        .perform(
            withSession(
                get("/api/v1/orders/{code}/status", "ANY").param("businessSlug", JOES), admin))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(withSession(post("/api/v1/handoffs"), admin))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void otherApiPathsAreClosedEvenWithTheServiceKey() throws Exception {
    mockMvc
        .perform(get("/api/v1/something-else").header("X-Api-Key", "test-key"))
        .andExpect(status().isUnauthorized());
  }
}
