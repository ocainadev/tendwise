package dev.ocaina.tendwise.settings;

import static dev.ocaina.tendwise.panel.PanelRequests.tokenFor;
import static dev.ocaina.tendwise.panel.PanelRequests.withSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ocaina.tendwise.TestcontainersConfiguration;
import dev.ocaina.tendwise.auth.AccessTokens;
import dev.ocaina.tendwise.user.MembershipRole;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The settings API (T53): read, replace, validation and who may use it. */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SettingsApiTest {

  private static final String URL = "/api/v1/panel/{slug}/settings";

  @Autowired private MockMvc mockMvc;
  @Autowired private AccessTokens accessTokens;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  private String slug;
  private String ownerToken;

  /** A fresh business for each test, so a PUT never changes the seeded demo businesses. */
  @BeforeEach
  void setUp() {
    slug = "settings-" + UUID.randomUUID().toString().substring(0, 8);
    jdbc.update(
        """
        INSERT INTO core.businesses
          (slug, name, category, default_language, timezone, publishable_key, opening_hours)
        VALUES (?, 'Bella Bakery', 'bakery', 'pt', 'America/Sao_Paulo', ?,
                '{"sun": [], "mon": [{"open": "08:00", "close": "18:00"}]}')
        """,
        slug,
        "pk_test_" + slug);
    ownerToken = tokenFor(accessTokens, slug, MembershipRole.OWNER);
  }

  /** A valid PUT body; tests change one field to make it invalid. */
  private ObjectNode validBody() throws Exception {
    return (ObjectNode)
        objectMapper.readTree(
            """
            {
              "name": "Bella Bakery & Café",
              "category": "cafe",
              "defaultLanguage": "es",
              "timezone": "Europe/Madrid",
              "openingHours": {
                "tue": [{"open": "07:30", "close": "14:00"}, {"open": "17:00", "close": "21:00"}],
                "mon": [],
                "sat": [{"open": "20:00", "close": "02:00"}]
              },
              "assistantSettings": {
                "name": "Bella",
                "tone": "warm and short",
                "greeting": "¡Hola! Soy Bella.",
                "extraInstructions": "Never promise delivery times.",
                "languages": ["es", "en"],
                "outOfHoursMessage": "We are closed now; we will answer tomorrow."
              },
              "handoffSettings": {"autoAckMinutes": 5},
              "widgetSettings": {
                "color": "#1D4ED8",
                "position": "bottom-left",
                "allowedDomains": ["https://bella.example.com", "http://localhost:5173"]
              }
            }
            """);
  }

  private ResultActions putSettings(Object body, String token) throws Exception {
    return mockMvc.perform(
        withSession(put(URL, slug), token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.toString()));
  }

  /** PUT a body that must fail, and check the field named in the problem. */
  private void expectFieldError(ObjectNode body, String field) throws Exception {
    putSettings(body, ownerToken)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Invalid request"))
        .andExpect(jsonPath("$.errors[*].field", hasItem(field)));
  }

  @Test
  void ownerReadsTheCurrentSettingsWithDefaults() throws Exception {
    mockMvc
        .perform(withSession(get(URL, slug), ownerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Bella Bakery"))
        .andExpect(jsonPath("$.category").value("bakery"))
        .andExpect(jsonPath("$.defaultLanguage").value("pt"))
        .andExpect(jsonPath("$.timezone").value("America/Sao_Paulo"))
        .andExpect(jsonPath("$.openingHours.mon[0].open").value("08:00"))
        .andExpect(jsonPath("$.openingHours.sun.length()").value(0))
        .andExpect(jsonPath("$.assistantSettings.languages", contains("en", "es", "pt")))
        .andExpect(jsonPath("$.assistantSettings.greeting").doesNotExist())
        .andExpect(jsonPath("$.handoffSettings.autoAckMinutes").value(3))
        .andExpect(jsonPath("$.widgetSettings.allowedDomains.length()").value(0));
  }

  @Test
  void putReplacesTheSettingsAndGetReturnsThem() throws Exception {
    putSettings(validBody(), ownerToken)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Bella Bakery & Café"));

    mockMvc
        .perform(withSession(get(URL, slug), ownerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Bella Bakery & Café"))
        .andExpect(jsonPath("$.category").value("cafe"))
        .andExpect(jsonPath("$.defaultLanguage").value("es"))
        .andExpect(jsonPath("$.timezone").value("Europe/Madrid"))
        .andExpect(jsonPath("$.openingHours.tue[1].close").value("21:00"))
        .andExpect(jsonPath("$.openingHours.sat[0].close").value("02:00"))
        .andExpect(jsonPath("$.openingHours.sun").doesNotExist())
        .andExpect(jsonPath("$.assistantSettings.name").value("Bella"))
        .andExpect(jsonPath("$.assistantSettings.tone").value("warm and short"))
        .andExpect(jsonPath("$.assistantSettings.greeting").value("¡Hola! Soy Bella."))
        .andExpect(
            jsonPath("$.assistantSettings.extraInstructions")
                .value("Never promise delivery times."))
        .andExpect(jsonPath("$.assistantSettings.languages", contains("es", "en")))
        .andExpect(
            jsonPath("$.assistantSettings.outOfHoursMessage")
                .value("We are closed now; we will answer tomorrow."))
        .andExpect(jsonPath("$.handoffSettings.autoAckMinutes").value(5))
        .andExpect(jsonPath("$.widgetSettings.color").value("#1D4ED8"))
        .andExpect(jsonPath("$.widgetSettings.position").value("bottom-left"))
        .andExpect(
            jsonPath("$.widgetSettings.allowedDomains[0]").value("https://bella.example.com"));
  }

  @Test
  void putKeepsOtherHandoffSettingsKeys() throws Exception {
    jdbc.update(
        "UPDATE core.businesses SET handoff_settings = "
            + "'{\"autoAckMinutes\": 3, \"waitingAlertMinutes\": 15}' WHERE slug = ?",
        slug);

    putSettings(validBody(), ownerToken).andExpect(status().isOk());

    String stored =
        jdbc.queryForObject(
            "SELECT handoff_settings::text FROM core.businesses WHERE slug = ?",
            String.class,
            slug);
    assertThat(objectMapper.readTree(stored).get("waitingAlertMinutes").asInt()).isEqualTo(15);
    assertThat(objectMapper.readTree(stored).get("autoAckMinutes").asInt()).isEqualTo(5);
  }

  @Test
  void anAgentCannotReadOrChangeTheSettings() throws Exception {
    String agentToken = tokenFor(accessTokens, slug, MembershipRole.AGENT);

    putSettings(validBody(), agentToken).andExpect(status().isForbidden());
    mockMvc.perform(withSession(get(URL, slug), agentToken)).andExpect(status().isForbidden());

    String name =
        jdbc.queryForObject("SELECT name FROM core.businesses WHERE slug = ?", String.class, slug);
    assertThat(name).isEqualTo("Bella Bakery");
  }

  @Test
  void theOwnerOfAnotherBusinessGets404AndChangesNothing() throws Exception {
    String otherOwner = tokenFor(accessTokens, "denim-corner", MembershipRole.OWNER);

    putSettings(validBody(), otherOwner).andExpect(status().isNotFound());
    mockMvc.perform(withSession(get(URL, slug), otherOwner)).andExpect(status().isNotFound());

    String name =
        jdbc.queryForObject("SELECT name FROM core.businesses WHERE slug = ?", String.class, slug);
    assertThat(name).isEqualTo("Bella Bakery");
  }

  @Test
  void extraInstructionsOverOneThousandCharactersAreRejected() throws Exception {
    ObjectNode body = validBody();
    ((ObjectNode) body.get("assistantSettings")).put("extraInstructions", "x".repeat(1001));
    expectFieldError(body, "assistantSettings.extraInstructions");

    ((ObjectNode) body.get("assistantSettings")).put("extraInstructions", "x".repeat(1000));
    putSettings(body, ownerToken).andExpect(status().isOk());
  }

  @Test
  void unknownFieldsAreRejected() throws Exception {
    ObjectNode body = validBody();
    body.put("plan", "free");
    expectFieldError(body, "plan");

    ObjectNode nested = validBody();
    ((ObjectNode) nested.get("widgetSettings")).put("script", "<script>");
    expectFieldError(nested, "widgetSettings.script");
  }

  @Test
  void wrongTypesAreRejected() throws Exception {
    ObjectNode body = validBody();
    ((ObjectNode) body.get("handoffSettings")).put("autoAckMinutes", 2.5);
    expectFieldError(body, "handoffSettings.autoAckMinutes");
  }

  @Test
  void invalidTimezoneIsRejected() throws Exception {
    ObjectNode body = validBody();
    body.put("timezone", "Mars/Olympus");
    expectFieldError(body, "timezone");
  }

  @Test
  void invalidOpeningHoursAreRejected() throws Exception {
    ObjectNode badTime = validBody();
    ((ObjectNode) badTime.get("openingHours"))
        .set("wed", objectMapper.readTree("[{\"open\": \"25:00\", \"close\": \"18:00\"}]"));
    expectFieldError(badTime, "openingHours.wed[0].open");

    ObjectNode badDay = validBody();
    ((ObjectNode) badDay.get("openingHours")).set("monday", objectMapper.readTree("[]"));
    expectFieldError(badDay, "openingHours.monday");

    ObjectNode sameTimes = validBody();
    ((ObjectNode) sameTimes.get("openingHours"))
        .set("wed", objectMapper.readTree("[{\"open\": \"09:00\", \"close\": \"09:00\"}]"));
    expectFieldError(sameTimes, "openingHours.wed[0]");
  }

  @Test
  void invalidColourAndPositionAreRejected() throws Exception {
    ObjectNode body = validBody();
    ((ObjectNode) body.get("widgetSettings")).put("color", "blue");
    ((ObjectNode) body.get("widgetSettings")).put("position", "top");
    putSettings(body, ownerToken)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[*].field", hasItem("widgetSettings.color")))
        .andExpect(jsonPath("$.errors[*].field", hasItem("widgetSettings.position")));
  }

  @Test
  void allowedDomainsMustBeOrigins() throws Exception {
    for (String domain :
        new String[] {
          "shop.example.com",
          "ftp://shop.example.com",
          "https://shop.example.com/page",
          "https://user@shop.example.com",
          "https://shop.example.com?x=1"
        }) {
      ObjectNode body = validBody();
      ((ObjectNode) body.get("widgetSettings"))
          .set("allowedDomains", objectMapper.createArrayNode().add(domain));
      expectFieldError(body, "widgetSettings.allowedDomains[0]");
    }
  }

  @Test
  void languagesMustBeKnownAndIncludeTheDefault() throws Exception {
    ObjectNode unknown = validBody();
    ((ObjectNode) unknown.get("assistantSettings"))
        .set("languages", objectMapper.createArrayNode().add("es").add("fr"));
    expectFieldError(unknown, "assistantSettings.languages[1]");

    ObjectNode none = validBody();
    ((ObjectNode) none.get("assistantSettings")).set("languages", objectMapper.createArrayNode());
    expectFieldError(none, "assistantSettings.languages");

    ObjectNode withoutDefault = validBody();
    withoutDefault.put("defaultLanguage", "pt");
    expectFieldError(withoutDefault, "defaultLanguage");
  }

  @Test
  void missingSectionsAndBlankNameAreRejected() throws Exception {
    ObjectNode body = validBody();
    body.remove("widgetSettings");
    body.put("name", " ");
    putSettings(body, ownerToken)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[*].field", hasItem("widgetSettings")))
        .andExpect(jsonPath("$.errors[*].field", hasItem("name")));
  }

  @Test
  void serviceRouteReturnsTheAssistantSettingsAndKeepsTheOldFields() throws Exception {
    putSettings(validBody(), ownerToken).andExpect(status().isOk());

    mockMvc
        .perform(get("/api/v1/businesses/{slug}", slug).header("X-Api-Key", "test-key"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value(slug))
        .andExpect(jsonPath("$.name").value("Bella Bakery & Café"))
        .andExpect(jsonPath("$.category").value("cafe"))
        .andExpect(jsonPath("$.defaultLanguage").value("es"))
        .andExpect(jsonPath("$.timezone").value("Europe/Madrid"))
        .andExpect(jsonPath("$.openingHours.tue[0].open").value("07:30"))
        .andExpect(jsonPath("$.assistantSettings.name").value("Bella"))
        .andExpect(jsonPath("$.assistantSettings.languages", contains("es", "en")))
        .andExpect(
            jsonPath("$.assistantSettings.extraInstructions")
                .value("Never promise delivery times."))
        .andExpect(jsonPath("$.widgetSettings").doesNotExist())
        .andExpect(jsonPath("$.id").doesNotExist());
  }
}
