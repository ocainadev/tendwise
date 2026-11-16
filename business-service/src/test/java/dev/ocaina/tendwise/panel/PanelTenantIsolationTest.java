package dev.ocaina.tendwise.panel;

import static dev.ocaina.tendwise.panel.PanelRequests.tokenFor;
import static dev.ocaina.tendwise.panel.PanelRequests.withSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ocaina.tendwise.TestcontainersConfiguration;
import dev.ocaina.tendwise.auth.AccessTokens;
import dev.ocaina.tendwise.user.MembershipRole;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Tenant isolation (docs/architecture.md, Security): every panel route, called by a user of another
 * business, answers 404.
 *
 * <p><b>Every task that adds a panel route appends it to {@link #ROUTES}.</b> {@link
 * #everyPanelRouteIsInTheSuite()} fails when one is missing.
 */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestPanelRoutes.class})
class PanelTenantIsolationTest {

  /** The route as the controller declares it, with {slug} and any other variables. */
  record PanelRoute(HttpMethod method, String path) {

    /** Fills {slug} with the business and any other variable (an id) with 1. */
    String urlFor(String slug) {
      return path.replace("{slug}", slug).replaceAll("\\{[^}]+}", "1");
    }

    @Override
    public String toString() {
      return method + " " + path;
    }
  }

  static final List<PanelRoute> ROUTES =
      List.of(
          // Test-only routes (TestPanelRoutes), until the first real panel route (T43).
          new PanelRoute(HttpMethod.GET, TestPanelRoutes.MEMBER_ROUTE),
          new PanelRoute(HttpMethod.POST, TestPanelRoutes.MEMBER_ROUTE),
          new PanelRoute(HttpMethod.POST, TestPanelRoutes.OWNER_ROUTE),
          // T34: team and invitations.
          new PanelRoute(HttpMethod.GET, "/api/v1/panel/{slug}/team"),
          new PanelRoute(HttpMethod.PATCH, "/api/v1/panel/{slug}/team/{userId}"),
          new PanelRoute(HttpMethod.DELETE, "/api/v1/panel/{slug}/team/{userId}"),
          new PanelRoute(HttpMethod.GET, "/api/v1/panel/{slug}/invitations"),
          new PanelRoute(HttpMethod.POST, "/api/v1/panel/{slug}/invitations"),
          new PanelRoute(HttpMethod.DELETE, "/api/v1/panel/{slug}/invitations/{id}"));

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokens accessTokens;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  static Stream<PanelRoute> routes() {
    return ROUTES.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("routes")
  void anOwnerOfAnotherBusinessGets404(PanelRoute route) throws Exception {
    String denimOwner = tokenFor(accessTokens, "denim-corner", MembershipRole.OWNER);

    mockMvc
        .perform(
            withSession(request(route.method(), route.urlFor("joes-pizza")), denimOwner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("routes")
  void anUnknownBusinessGets404(PanelRoute route) throws Exception {
    String denimOwner = tokenFor(accessTokens, "denim-corner", MembershipRole.OWNER);

    mockMvc
        .perform(
            withSession(request(route.method(), route.urlFor("no-such-business")), denimOwner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void everyPanelRouteIsInTheSuite() {
    List<PanelRoute> mapped =
        handlerMapping.getHandlerMethods().keySet().stream()
            .flatMap(info ->
                info.getPatternValues().stream()
                    .filter(pattern -> pattern.startsWith(PanelGuard.PREFIX))
                    .flatMap(pattern ->
                        info.getMethodsCondition().getMethods().stream()
                            .map(m -> new PanelRoute(HttpMethod.valueOf(m.name()), pattern))))
            .toList();

    assertThat(ROUTES)
        .as("panel routes missing from PanelTenantIsolationTest.ROUTES")
        .containsAll(mapped);
  }
}
