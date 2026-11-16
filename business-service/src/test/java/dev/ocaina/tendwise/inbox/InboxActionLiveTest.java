package dev.ocaina.tendwise.inbox;

import static dev.ocaina.tendwise.panel.PanelRequests.tokenFor;
import static dev.ocaina.tendwise.panel.PanelRequests.withSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ocaina.tendwise.TestcontainersConfiguration;
import dev.ocaina.tendwise.auth.AccessTokens;
import dev.ocaina.tendwise.auth.AuthCookies;
import dev.ocaina.tendwise.auth.SecureTokens;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.conversation.Channel;
import dev.ocaina.tendwise.conversation.ConversationService;
import dev.ocaina.tendwise.handoff.CreateHandoffRequest;
import dev.ocaina.tendwise.handoff.HandoffReason;
import dev.ocaina.tendwise.handoff.HandoffService;
import dev.ocaina.tendwise.realtime.SseTestClient;
import dev.ocaina.tendwise.realtime.SseTestClient.Event;
import dev.ocaina.tendwise.user.Membership;
import dev.ocaina.tendwise.user.MembershipRepository;
import dev.ocaina.tendwise.user.MembershipRole;
import dev.ocaina.tendwise.user.User;
import dev.ocaina.tendwise.user.UserRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The inbox actions with real commits (T44): the takeover race through the API, and the live events
 * the actions send to the panel and widget streams (real HTTP SSE, PostgreSQL LISTEN/NOTIFY). Not
 * {@code @Transactional}: every request commits, as in production. Every test uses new users and
 * conversations.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "tendwise.api-key=test-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InboxActionLiveTest {

  private static final String JOES = "joes-pizza";
  private static final String ACTION = "/api/v1/panel/{slug}/conversations/{id}/{action}";
  private static final Duration QUIET = Duration.ofSeconds(1);

  @LocalServerPort private int port;
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AccessTokens accessTokens;
  @Autowired private BusinessRepository businesses;
  @Autowired private ConversationService conversationService;
  @Autowired private HandoffService handoffService;
  @Autowired private UserRepository users;
  @Autowired private MembershipRepository memberships;
  @Autowired private JdbcTemplate jdbc;

  private long joesId;
  private User maria;
  private User joao;

  @BeforeEach
  void setUp() {
    // No AI_REPLY job of these tests may run anywhere else.
    jdbc.update("DELETE FROM core.jobs");
    joesId = businesses.findBySlug(JOES).orElseThrow().getId();
    maria = member("Maria Souza");
    joao = member("João Lima");
  }

  @Test
  void twoConcurrentTakeoversOneWinsTheOtherGets409WithTheWinnersName() throws Exception {
    UUID id = waiting(conversation()).id();
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Future<MockHttpServletResponse>> calls = new ArrayList<>();
    for (User user : List.of(maria, joao)) {
      Callable<MockHttpServletResponse> takeOver =
          () -> {
            go.await();
            return mockMvc
                .perform(withSession(post(ACTION, JOES, id, "takeover"), tokenOf(user)))
                .andReturn()
                .getResponse();
          };
      calls.add(pool.submit(takeOver));
    }
    go.countDown();
    List<MockHttpServletResponse> responses = new ArrayList<>();
    for (Future<MockHttpServletResponse> call : calls) {
      responses.add(call.get());
    }
    pool.shutdown();

    assertThat(responses)
        .extracting(MockHttpServletResponse::getStatus)
        .containsExactlyInAnyOrder(200, 409);
    MockHttpServletResponse winner =
        responses.stream().filter(r -> r.getStatus() == 200).findFirst().orElseThrow();
    MockHttpServletResponse loser =
        responses.stream().filter(r -> r.getStatus() == 409).findFirst().orElseThrow();
    String winnerName =
        objectMapper.readTree(winner.getContentAsString()).get("assignee").get("name").asText();
    JsonNode problem = objectMapper.readTree(loser.getContentAsString());
    assertThat(problem.get("state").asText()).isEqualTo("HUMAN");
    assertThat(problem.get("assigneeName").asText()).isEqualTo(winnerName);
    assertThat(
            jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM core.messages
                WHERE conversation_id = ? AND metadata ->> 'event' = 'TAKEN_OVER'
                """,
                Integer.class,
                id))
        .isEqualTo(1);
  }

  @Test
  void anInternalNoteReachesThePanelButNeverTheWidget() throws Exception {
    Chat chat = waiting(conversation());
    perform(chat.id(), "takeover", maria, null);
    try (SseTestClient widget = openWidget(chat);
        SseTestClient panel = openPanel()) {

      long note = messageId(perform(chat.id(), "messages", maria, body("Nota secreta", true)));
      long reply = messageId(perform(chat.id(), "messages", maria, body("Oi!", false)));

      List<Event> panelEvents = eventsAbout(panel, chat.id(), QUIET);
      assertThat(panelEvents)
          .extracting(Event::id)
          .containsExactly(String.valueOf(note), String.valueOf(reply));
      List<Event> widgetEvents = eventsAbout(widget, chat.id(), Duration.ofMillis(200));
      assertThat(widgetEvents).extracting(Event::id).containsExactly(String.valueOf(reply));
      // Ids only, never the text.
      assertThat(panelEvents).noneMatch(event -> event.data().contains("secreta"));
    }
  }

  @Test
  void everyActionSendsItsEventsAfterTheCommit() throws Exception {
    Chat chat = waiting(conversation());
    try (SseTestClient panel = openPanel()) {

      perform(chat.id(), "takeover", maria, null);
      assertThat(names(eventsAbout(panel, chat.id(), QUIET)))
          .containsExactly(
              "message.created", "conversation.state_changed", "conversation.assigned");

      perform(chat.id(), "assign", maria, "{\"userId\": " + joao.getId() + "}");
      assertThat(names(eventsAbout(panel, chat.id(), QUIET)))
          .containsExactly(
              "message.created", "conversation.state_changed", "conversation.assigned");

      perform(chat.id(), "read", joao, null);
      assertThat(names(eventsAbout(panel, chat.id(), QUIET))).containsExactly("conversation.read");

      perform(chat.id(), "release", joao, null);
      assertThat(names(eventsAbout(panel, chat.id(), QUIET)))
          .containsExactly(
              "message.created", "conversation.state_changed", "conversation.assigned");

      perform(chat.id(), "resolve", joao, null);
      List<Event> resolved = eventsAbout(panel, chat.id(), QUIET);
      assertThat(names(resolved)).containsExactly("message.created", "conversation.state_changed");
      assertThat(objectMapper.readTree(resolved.get(1).data()).get("state").asText())
          .isEqualTo("RESOLVED");
    }
  }

  @Test
  void staffTypingReachesTheWidgetWithTheFirstNameAtMostOnceEveryThreeSeconds() throws Exception {
    Chat chat = waiting(conversation());
    perform(chat.id(), "takeover", maria, null);
    try (SseTestClient widget = openWidget(chat)) {

      perform(chat.id(), "typing", maria, null);
      perform(chat.id(), "typing", maria, null);

      List<Event> events = eventsAbout(widget, chat.id(), QUIET);
      assertThat(names(events)).containsExactly("staff.typing");
      assertThat(objectMapper.readTree(events.getFirst().data()).get("name").asText())
          .isEqualTo("Maria");
      // Nothing is stored.
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM core.messages WHERE conversation_id = ? AND sender = 'HUMAN'",
                  Integer.class,
                  chat.id()))
          .isZero();
    }
  }

  // --- helpers --------------------------------------------------------------------------------

  private record Chat(UUID id, String token) {}

  private User member(String name) {
    User user =
        users.save(
            new User("staff-" + UUID.randomUUID() + "@live.test", "not-a-real-hash", name, null));
    memberships.save(new Membership(user.getId(), joesId, MembershipRole.AGENT));
    return user;
  }

  private String tokenOf(User user) {
    return tokenFor(accessTokens, user.getId(), JOES, MembershipRole.AGENT);
  }

  private Chat conversation() {
    String token = SecureTokens.generate();
    UUID id =
        conversationService
            .start(
                joesId,
                Channel.WEB,
                "pt",
                UUID.randomUUID().toString(),
                SecureTokens.sha256Hex(token))
            .getId();
    return new Chat(id, token);
  }

  private Chat waiting(Chat chat) {
    handoffService.create(
        new CreateHandoffRequest(JOES, chat.id(), HandoffReason.HUMAN_REQUEST, "Resumo"));
    return chat;
  }

  /** Runs an action and expects a 2xx answer. json: the body, or null for none. */
  private MockHttpServletResponse perform(UUID id, String action, User user, String json)
      throws Exception {
    var request = withSession(post(ACTION, JOES, id, action), tokenOf(user));
    if (json != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(json);
    }
    MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
    assertThat(response.getStatus())
        .as(action + ": " + response.getContentAsString())
        .isBetween(200, 299);
    return response;
  }

  private String body(String text, boolean internal) throws Exception {
    return objectMapper.writeValueAsString(Map.of("body", text, "internal", internal));
  }

  private long messageId(MockHttpServletResponse response) throws Exception {
    return objectMapper.readTree(response.getContentAsString()).get("id").asLong();
  }

  private SseTestClient openWidget(Chat chat) throws Exception {
    SseTestClient client =
        SseTestClient.open(
            "http://localhost:" + port + "/public/v1/conversations/" + chat.id() + "/events",
            Map.of("Authorization", "Bearer " + chat.token()));
    assertThat(client.status()).as(client.status() == 200 ? "" : client.errorBody()).isEqualTo(200);
    return client;
  }

  private SseTestClient openPanel() throws Exception {
    SseTestClient client =
        SseTestClient.open(
            "http://localhost:" + port + "/api/v1/panel/" + JOES + "/events",
            Map.of("Cookie", AuthCookies.ACCESS + "=" + tokenOf(maria)));
    assertThat(client.status()).as(client.status() == 200 ? "" : client.errorBody()).isEqualTo(200);
    return client;
  }

  private List<Event> eventsAbout(SseTestClient client, UUID conversationId, Duration during)
      throws Exception {
    return client.collect(during).stream().filter(event -> isAbout(event, conversationId)).toList();
  }

  private boolean isAbout(Event event, UUID conversationId) {
    try {
      JsonNode conversation = objectMapper.readTree(event.data()).get("conversationId");
      return conversation != null && conversation.asText().equals(conversationId.toString());
    } catch (Exception e) {
      return false;
    }
  }

  private static List<String> names(List<Event> events) {
    return events.stream().map(Event::name).toList();
  }
}
