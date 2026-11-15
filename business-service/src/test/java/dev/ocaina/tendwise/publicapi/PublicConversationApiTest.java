package dev.ocaina.tendwise.publicapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ocaina.tendwise.TestcontainersConfiguration;
import dev.ocaina.tendwise.auth.SecureTokens;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.conversation.Conversation;
import dev.ocaina.tendwise.conversation.ConversationRepository;
import dev.ocaina.tendwise.conversation.ConversationService;
import dev.ocaina.tendwise.conversation.ConversationState;
import dev.ocaina.tendwise.handoff.HandoffReason;
import dev.ocaina.tendwise.job.Job;
import dev.ocaina.tendwise.job.JobRepository;
import dev.ocaina.tendwise.job.JobType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The widget's API end to end: security chain, controller, service, state machine and the seeded
 * PostgreSQL (joes-pizza and denim-corner, both allowing http://localhost:5173).
 *
 * <p>{@code @Transactional} rolls back every test. Each test calls from its own IP, so the per-IP
 * limit (30 POSTs per minute) of one test never affects another.
 */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class PublicConversationApiTest {

  /** handOff only records the handoff id in the SYSTEM message; these tests need no handoff row. */
  private static final long ANY_HANDOFF_ID = 1L;

  private static final String JOES_KEY = "pk_demo_joes_pizza";
  private static final String DENIM_KEY = "pk_demo_denim_corner";
  private static final String DEMO_ORIGIN = "http://localhost:5173";
  private static final String FOREIGN_ORIGIN = "https://evil.example";

  private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ConversationRepository conversations;
  @Autowired private ConversationService conversationService;
  @Autowired private BusinessRepository businesses;
  @Autowired private JobRepository jobs;
  @Autowired private JdbcTemplate jdbc;

  /** The client IP of this test (documentation range 198.51.100.0/24 and up). */
  private String ip;

  @BeforeEach
  void useOwnIp() {
    int n = NEXT_IP.getAndIncrement();
    ip = "198.51." + (100 + n / 250) + "." + (n % 250 + 1);
  }

  // --- Happy path -------------------------------------------------------------------------------

  @Test
  void startSendAndHistory() throws Exception {
    JsonNode started =
        readJson(
            start(JOES_KEY, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conversationId").isString())
                .andExpect(
                    jsonPath("$.greeting")
                        .value(org.hamcrest.Matchers.containsString("Joe's Pizza"))));
    UUID id = UUID.fromString(started.get("conversationId").asText());
    String token = started.get("token").asText();
    // 256 random bits in base64url.
    assertThat(token).hasSize(43);

    JsonNode sent =
        readJson(
            send(id, token, "Do you deliver?")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.sender").value("CUSTOMER"))
                .andExpect(jsonPath("$.body").value("Do you deliver?"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.authorName").doesNotExist())
                .andExpect(jsonPath("$.metadata").doesNotExist()));
    long messageId = sent.get("id").asLong();

    history(id, token, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(messageId))
        .andExpect(jsonPath("$[0].body").value("Do you deliver?"));
    history(id, token, messageId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void startStoresAWebConversationWithOnlyTheTokenHash() throws Exception {
    JsonNode started = readJson(start(JOES_KEY, "es").andExpect(status().isCreated()));
    String token = started.get("token").asText();

    Conversation conversation =
        conversations
            .findById(UUID.fromString(started.get("conversationId").asText()))
            .orElseThrow();
    assertThat(conversation.getBusinessId()).isEqualTo(businessId("joes-pizza"));
    assertThat(conversation.getState()).isEqualTo(ConversationState.AI);
    assertThat(conversation.getLanguage()).isEqualTo("es");
    assertThat(conversation.getTokenHash()).isEqualTo(SecureTokens.sha256Hex(token));
    assertThat(conversation.getTokenHash()).isNotEqualTo(token);
    assertThat(started.get("greeting").asText()).startsWith("¡Hola!");
  }

  @Test
  void greetingUsesTheBusinessLanguageWhenNoneIsGiven() throws Exception {
    start(DENIM_KEY, null)
        .andExpect(status().isCreated())
        .andExpect(
            jsonPath("$.greeting")
                .value(
                    "Olá! Sou o assistente virtual de Denim Corner. Como posso ajudar você hoje?"));
  }

  @Test
  void sendInStateAiQueuesAnAiReplyJob() throws Exception {
    Started started = startConversation(JOES_KEY);

    long messageId =
        readJson(send(started.id(), started.token(), "Hi").andExpect(status().isAccepted()))
            .get("id")
            .asLong();

    List<Job> queued = jobsFor(started.id());
    assertThat(queued).hasSize(1);
    assertThat(queued.get(0).getType()).isEqualTo(JobType.AI_REPLY);
    Map<String, Object> payload = queued.get(0).getPayload();
    assertThat(((Number) payload.get("messageId")).longValue()).isEqualTo(messageId);
    assertThat(((Number) payload.get("businessId")).longValue())
        .isEqualTo(businessId("joes-pizza"));
  }

  @Test
  void sendWhileWaitingForStaffQueuesNoJob() throws Exception {
    Started started = startConversation(JOES_KEY);
    conversationService.handOff(
        started.id(), businessId("joes-pizza"), HandoffReason.HUMAN_REQUEST, ANY_HANDOFF_ID);

    send(started.id(), started.token(), "Anyone there?").andExpect(status().isAccepted());

    assertThat(jobsFor(started.id())).isEmpty();
  }

  @Test
  void historyHidesInternalMessagesAndShowsTheStaffFirstName() throws Exception {
    Started started = startConversation(JOES_KEY);
    Long businessId = businessId("joes-pizza");
    Long userId =
        jdbc.queryForObject(
            "INSERT INTO core.users (email, password_hash, name) VALUES ('maria@joes-pizza.test', 'x', 'Maria Silva') RETURNING id",
            Long.class);
    conversationService.handOff(
        started.id(), businessId, HandoffReason.HUMAN_REQUEST, ANY_HANDOFF_ID); // internal
    conversationService.takeOver(started.id(), businessId, userId); // visible
    jdbc.update(
        "INSERT INTO core.messages (conversation_id, business_id, sender, author_user_id, body, created_at)"
            + " VALUES (?, ?, 'HUMAN', ?, 'Hi, I can help.', now())",
        started.id(),
        businessId,
        userId);

    history(started.id(), started.token(), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].sender").value("SYSTEM"))
        .andExpect(jsonPath("$[0].body").value("A team member joined the conversation."))
        .andExpect(jsonPath("$[0].metadata").doesNotExist())
        .andExpect(jsonPath("$[1].sender").value("HUMAN"))
        .andExpect(jsonPath("$[1].authorName").value("Maria"))
        .andExpect(jsonPath("$[1].authorUserId").doesNotExist());
  }

  @Test
  void publicRoutesNeedNoOriginHeader() throws Exception {
    // Not from another website (e.g. same-origin page, curl): the key and token still apply.
    mockMvc
        .perform(
            withIp(post("/public/v1/conversations"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("publishableKey", JOES_KEY))))
        .andExpect(status().isCreated());
  }

  // --- Key and token ----------------------------------------------------------------------------

  @Test
  void unknownPublishableKeyReturns404() throws Exception {
    start("pk_does_not_exist", null)
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Business not found"));
  }

  @Test
  void wrongMissingOrMalformedTokenReturnsTheSame404() throws Exception {
    Started started = startConversation(JOES_KEY);

    expectConversationNotFound(send(started.id(), SecureTokens.generate(), "Hi"));
    expectConversationNotFound(history(started.id(), SecureTokens.generate(), null));
    expectConversationNotFound(sendWithAuthorization(started.id(), null));
    expectConversationNotFound(sendWithAuthorization(started.id(), started.token()));
    expectConversationNotFound(sendWithAuthorization(started.id(), "Bearer "));
    // The right token on an unknown id.
    expectConversationNotFound(send(UUID.randomUUID(), started.token(), "Hi"));
    expectConversationNotFound(history(UUID.randomUUID(), started.token(), null));
    assertThat(jobsFor(started.id())).isEmpty();
  }

  // --- Tenant isolation -------------------------------------------------------------------------

  @Test
  void aTokenOnlyOpensItsOwnConversation() throws Exception {
    Started joes = startConversation(JOES_KEY);
    Started denim = startConversation(DENIM_KEY);
    send(denim.id(), denim.token(), "Vocês têm calça 42?").andExpect(status().isAccepted());

    // Another business's conversation with this business's token: same 404 as a wrong token.
    expectConversationNotFound(send(denim.id(), joes.token(), "Hi"));
    expectConversationNotFound(history(denim.id(), joes.token(), null));
    history(joes.id(), joes.token(), null).andExpect(jsonPath("$.length()").value(0));

    assertThat(conversations.findById(joes.id()).orElseThrow().getBusinessId())
        .isEqualTo(businessId("joes-pizza"));
    assertThat(conversations.findById(denim.id()).orElseThrow().getBusinessId())
        .isEqualTo(businessId("denim-corner"));
  }

  // --- Origin and CORS --------------------------------------------------------------------------

  @Test
  void foreignOriginIsRejectedWith403() throws Exception {
    long before = conversations.count();
    expectOriginNotAllowed(
        mockMvc.perform(
            withIp(post("/public/v1/conversations"))
                .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("publishableKey", JOES_KEY)))));
    assertThat(conversations.count()).isEqualTo(before);

    Started started = startConversation(JOES_KEY);
    expectOriginNotAllowed(
        mockMvc.perform(
            withIp(post("/public/v1/conversations/" + started.id() + "/messages"))
                .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + started.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("body", "Hi")))));
    expectOriginNotAllowed(
        mockMvc.perform(
            get("/public/v1/conversations/" + started.id() + "/messages")
                .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + started.token())));
    assertThat(jobsFor(started.id())).isEmpty();
  }

  @Test
  void anOriginOfOneBusinessIsNotAllowedForAnother() throws Exception {
    jdbc.update(
        "UPDATE core.businesses SET widget_settings = '{\"allowedDomains\": [\"https://denim.example\"]}' WHERE slug = 'denim-corner'");

    startWithOrigin(DENIM_KEY, "https://denim.example").andExpect(status().isCreated());
    expectOriginNotAllowed(startWithOrigin(JOES_KEY, "https://denim.example"));
    expectOriginNotAllowed(startWithOrigin(DENIM_KEY, DEMO_ORIGIN));
  }

  @Test
  void corsPreflightAllowsTheWidgetHeaders() throws Exception {
    mockMvc
        .perform(
            options("/public/v1/conversations/" + UUID.randomUUID() + "/messages")
                .header(HttpHeaders.ORIGIN, DEMO_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization, content-type"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*"))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
  }

  @Test
  void otherRoutesHaveNoCors() throws Exception {
    mockMvc
        .perform(
            options("/auth/login")
                .header(HttpHeaders.ORIGIN, DEMO_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
  }

  // --- Limits -----------------------------------------------------------------------------------

  @Test
  void messageLengthIsLimitedTo4000Characters() throws Exception {
    Started started = startConversation(JOES_KEY);

    send(started.id(), started.token(), "a".repeat(4000)).andExpect(status().isAccepted());
    send(started.id(), started.token(), "a".repeat(4001))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    send(started.id(), started.token(), "   ").andExpect(status().isBadRequest());
    assertThat(jobsFor(started.id())).hasSize(1);
  }

  @Test
  void conversationRateLimitReturns429After20Messages() throws Exception {
    Started started = startConversation(JOES_KEY);
    for (int i = 0; i < 20; i++) {
      send(started.id(), started.token(), "Message " + i).andExpect(status().isAccepted());
    }

    expectTooManyRequests(send(started.id(), started.token(), "One more"));
    assertThat(jobsFor(started.id())).hasSize(20);
  }

  @Test
  void ipRateLimitReturns429After30Requests() throws Exception {
    for (int i = 0; i < 30; i++) {
      start(JOES_KEY, null).andExpect(status().isCreated());
    }

    expectTooManyRequests(start(JOES_KEY, null));
    // Another IP is not affected.
    useOwnIp();
    start(JOES_KEY, null).andExpect(status().isCreated());
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private record Started(UUID id, String token) {}

  private Started startConversation(String publishableKey) throws Exception {
    JsonNode json = readJson(start(publishableKey, null).andExpect(status().isCreated()));
    return new Started(
        UUID.fromString(json.get("conversationId").asText()), json.get("token").asText());
  }

  private ResultActions start(String publishableKey, String language) throws Exception {
    Map<String, String> body =
        language == null
            ? Map.of("publishableKey", publishableKey)
            : Map.of("publishableKey", publishableKey, "language", language);
    return mockMvc.perform(
        withIp(post("/public/v1/conversations"))
            .header(HttpHeaders.ORIGIN, DEMO_ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(body)));
  }

  private ResultActions startWithOrigin(String publishableKey, String origin) throws Exception {
    return mockMvc.perform(
        withIp(post("/public/v1/conversations"))
            .header(HttpHeaders.ORIGIN, origin)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("publishableKey", publishableKey))));
  }

  private ResultActions send(UUID id, String token, String body) throws Exception {
    return mockMvc.perform(
        withIp(post("/public/v1/conversations/" + id + "/messages"))
            .header(HttpHeaders.ORIGIN, DEMO_ORIGIN)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("body", body))));
  }

  /** authorization is the whole header value; null sends no header. */
  private ResultActions sendWithAuthorization(UUID id, String authorization) throws Exception {
    MockHttpServletRequestBuilder request =
        withIp(post("/public/v1/conversations/" + id + "/messages"))
            .header(HttpHeaders.ORIGIN, DEMO_ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("body", "Hi")));
    if (authorization != null) {
      request.header(HttpHeaders.AUTHORIZATION, authorization);
    }
    return mockMvc.perform(request);
  }

  private ResultActions history(UUID id, String token, Long after) throws Exception {
    MockHttpServletRequestBuilder request =
        get("/public/v1/conversations/" + id + "/messages")
            .header(HttpHeaders.ORIGIN, DEMO_ORIGIN)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    if (after != null) {
      request.param("after", after.toString());
    }
    return mockMvc.perform(request);
  }

  private MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder request) {
    String clientIp = ip;
    return request.with(servletRequest -> {
      servletRequest.setRemoteAddr(clientIp);
      return servletRequest;
    });
  }

  private static void expectConversationNotFound(ResultActions result) throws Exception {
    result
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Conversation not found"))
        .andExpect(jsonPath("$.detail").value("No conversation with this id and token."));
  }

  private static void expectOriginNotAllowed(ResultActions result) throws Exception {
    result
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Origin not allowed"));
  }

  private static void expectTooManyRequests(ResultActions result) throws Exception {
    result
        .andExpect(status().isTooManyRequests())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Too many requests"));
  }

  private List<Job> jobsFor(UUID conversationId) {
    return jobs.findAll().stream()
        .filter(job -> conversationId.toString().equals(job.getPayload().get("conversationId")))
        .toList();
  }

  private Long businessId(String slug) {
    return businesses.findBySlug(slug).orElseThrow().getId();
  }

  private String json(Object value) throws Exception {
    return objectMapper.writeValueAsString(value);
  }

  private JsonNode readJson(ResultActions result) throws Exception {
    return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
  }
}
