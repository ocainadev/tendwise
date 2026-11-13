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
import java.time.Instant;
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
 * joes-pizza and denim-corner, no handoffs).
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

  @Test
  void createReturns201WithLocationAndOpenStatus() throws Exception {
    UUID conversationId = UUID.randomUUID();

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
    long resolved = createHandoff("joes-pizza", "LOW_CONFIDENCE");
    patchStatus(resolved, "joes-pizza", "RESOLVED").andExpect(status().isOk());

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
  void updateStatusReturnsTheUpdatedHandoff() throws Exception {
    ResultActions created =
        postHandoff(createBody("joes-pizza", UUID.randomUUID(), "COMPLAINT", "Cold pizza."));
    JsonNode before = readJson(created);
    long id = before.get("id").asLong();

    ResultActions updated = patchStatus(id, "joes-pizza", "IN_PROGRESS");

    updated
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.createdAt").value(before.get("createdAt").asText()));
    Instant updatedAtBefore = Instant.parse(before.get("updatedAt").asText());
    Instant updatedAtAfter = Instant.parse(readJson(updated).get("updatedAt").asText());
    assertThat(updatedAtAfter).isAfter(updatedAtBefore);
  }

  @Test
  void updateOfUnknownIdReturns404() throws Exception {
    expectHandoffNotFound(patchStatus(999_999, "joes-pizza", "RESOLVED"));
  }

  @Test
  void updateOfAnotherBusinessHandoffReturnsTheSame404() throws Exception {
    long denimHandoff = createHandoff("denim-corner", "COMPLAINT");

    expectHandoffNotFound(patchStatus(denimHandoff, "joes-pizza", "RESOLVED"));
  }

  @Test
  void updateWithMissingStatusReturns400() throws Exception {
    long id = createHandoff("joes-pizza", "COMPLAINT");

    var request =
        patch("/api/v1/handoffs/{id}", id)
            .param("businessSlug", "joes-pizza")
            .header("X-Api-Key", VALID_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}");
    expectBadRequest(mockMvc.perform(request));
  }

  @Test
  void missingApiKeyReturns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/handoffs").param("businessSlug", "joes-pizza"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  // --- helpers ---

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
        postHandoff(createBody(slug, UUID.randomUUID(), reason, "Summary."))
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

  private ResultActions patchStatus(long id, String slug, String status) throws Exception {
    return mockMvc.perform(
        patch("/api/v1/handoffs/{id}", id)
            .param("businessSlug", slug)
            .header("X-Api-Key", VALID_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"" + status + "\"}"));
  }

  private JsonNode readJson(ResultActions result) throws Exception {
    return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private void expectBadRequest(ResultActions result) throws Exception {
    result
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  /** Same status and title for an unknown id and an id of another business. */
  private void expectHandoffNotFound(ResultActions result) throws Exception {
    result
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Handoff not found"))
        .andExpect(jsonPath("$.detail").value("No handoff with this id in this business."));
  }
}
