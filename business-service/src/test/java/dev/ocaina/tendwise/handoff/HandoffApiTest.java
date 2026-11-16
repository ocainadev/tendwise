package dev.ocaina.tendwise.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ocaina.tendwise.TestcontainersConfiguration;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.conversation.Channel;
import dev.ocaina.tendwise.conversation.ConversationService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * Full stack test: API-key filter, controller, service and the seeded PostgreSQL (businesses
 * joes-pizza and denim-corner, no handoffs). Since T41 a handoff needs a conversation of its
 * business in state AI, so each handoff gets a new one (the conversation side is tested in
 * HandoffConversationTest).
 *
 * <p>{@code @Transactional} rolls back every test, so tests never see each other's handoffs.
 */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class HandoffApiTest {

  private static final String VALID_KEY = "test-key";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private BusinessRepository businesses;
  @Autowired private ConversationService conversations;

  @Test
  void createReturns201WithLocationAndOpenStatus() throws Exception {
    UUID conversationId = newConversation("joes-pizza");

    ResultActions result =
        postHandoff(createBody("joes-pizza", conversationId, "COMPLAINT", "Cold pizza."));

    result
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNumber())
        .andExpect(jsonPath("$.conversationId").value(conversationId.toString()))
        .andExpect(jsonPath("$.reason").value("COMPLAINT"))
        .andExpect(jsonPath("$.summary").value("Cold pizza."))
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.createdAt").exists())
        .andExpect(jsonPath("$.updatedAt").exists())
        // The internal business id is not part of the response.
        .andExpect(jsonPath("$.businessId").doesNotExist());

    long id = readJson(result).get("id").asLong();
    result.andExpect(header().string("Location", "/api/v1/handoffs/" + id));
  }

  @Test
  void createForUnknownBusinessReturns404() throws Exception {
    postHandoff(createBody("no-such-business", UUID.randomUUID(), "COMPLAINT", "Cold pizza."))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Business not found"));
  }

  @Test
  void createWithBlankSummaryReturns400() throws Exception {
    expectBadRequest(postHandoff(createBody("joes-pizza", UUID.randomUUID(), "COMPLAINT", " ")));
  }

  @Test
  void createWithTooLongSummaryReturns400() throws Exception {
    String summary = "a".repeat(2001);
    expectBadRequest(
        postHandoff(createBody("joes-pizza", UUID.randomUUID(), "COMPLAINT", summary)));
  }

  @Test
  void createWithUnknownReasonReturns400() throws Exception {
    expectBadRequest(
        postHandoff(createBody("joes-pizza", UUID.randomUUID(), "BORED", "Cold pizza.")));
  }

  @Test
  void createWithMalformedConversationIdReturns400() throws Exception {
    String body =
        """
        {"businessSlug": "joes-pizza", "conversationId": "not-a-uuid",
         "reason": "COMPLAINT", "summary": "Cold pizza."}
        """;
    expectBadRequest(postHandoff(body));
  }

  @Test
  void createWithMissingFieldsReturns400() throws Exception {
    expectBadRequest(postHandoff("{\"businessSlug\": \"joes-pizza\"}"));
  }

  @Test
  void listWithoutStatusReturnsAllHandoffsOldestFirst() throws Exception {
    long first = createHandoff("joes-pizza", "COMPLAINT");
    long second = createHandoff("joes-pizza", "HUMAN_REQUEST");

    listHandoffs("joes-pizza", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(first))
        .andExpect(jsonPath("$[1].id").value(second));
  }

  @Test
  void listByStatusReturnsOnlyMatchingHandoffs() throws Exception {
    long stillOpen = createHandoff("joes-pizza", "COMPLAINT");
    UUID resolvedConversation = newConversation("joes-pizza");
    long resolved =
        readJson(
                postHandoff(
                    createBody("joes-pizza", resolvedConversation, "LOW_CONFIDENCE", "Summary.")))
            .get("id")
            .asLong();
    // T44: a handoff's status follows its conversation.
    Long joesId = businesses.findBySlug("joes-pizza").orElseThrow().getId();
    conversations.resolve(resolvedConversation, joesId, 1L);

    listHandoffs("joes-pizza", "OPEN")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(stillOpen));

    listHandoffs("joes-pizza", "RESOLVED")
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(resolved));
  }

  @Test
  void listNeverReturnsHandoffsOfAnotherBusiness() throws Exception {
    long pizzaHandoff = createHandoff("joes-pizza", "COMPLAINT");
    createHandoff("denim-corner", "COMPLAINT");

    listHandoffs("joes-pizza", null)
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(pizzaHandoff));
  }

  @Test
  void listWithUnknownStatusReturns400() throws Exception {
    expectBadRequest(listHandoffs("joes-pizza", "CLOSED"));
  }

  @Test
  void patchStatusIsGone() throws Exception {
    // T44: the status follows the conversation, so the route was removed.
    long id = createHandoff("joes-pizza", "COMPLAINT");

    int status =
        mockMvc
            .perform(
                patch("/api/v1/handoffs/{id}", id)
                    .param("businessSlug", "joes-pizza")
                    .header("X-Api-Key", VALID_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\": \"RESOLVED\"}"))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).isIn(404, 405);
  }

  @Test
  void missingApiKeyReturns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/handoffs").param("businessSlug", "joes-pizza"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  // --- helpers ---

  /** A new conversation of the business, in state AI. */
  private UUID newConversation(String slug) {
    Long businessId = businesses.findBySlug(slug).orElseThrow().getId();
    return conversations
        .start(businessId, Channel.WEB, "en", UUID.randomUUID().toString(), null)
        .getId();
  }

  private String createBody(String slug, UUID conversationId, String reason, String summary)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("businessSlug", slug);
    body.put("conversationId", conversationId);
    body.put("reason", reason);
    body.put("summary", summary);
    return objectMapper.writeValueAsString(body);
  }

  private ResultActions postHandoff(String body) throws Exception {
    return mockMvc.perform(
        post("/api/v1/handoffs")
            .header("X-Api-Key", VALID_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  /** Creates a handoff and returns its id. */
  private long createHandoff(String slug, String reason) throws Exception {
    ResultActions result =
        postHandoff(createBody(slug, newConversation(slug), reason, "Summary."))
            .andExpect(status().isCreated());
    return readJson(result).get("id").asLong();
  }

  private ResultActions listHandoffs(String slug, String status) throws Exception {
    var request =
        get("/api/v1/handoffs").param("businessSlug", slug).header("X-Api-Key", VALID_KEY);
    if (status != null) {
      request.param("status", status);
    }
    return mockMvc.perform(request);
  }

  private JsonNode readJson(ResultActions result) throws Exception {
    return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private void expectBadRequest(ResultActions result) throws Exception {
    result
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }
}
