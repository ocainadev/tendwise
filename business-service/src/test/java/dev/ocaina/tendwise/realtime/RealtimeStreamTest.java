package dev.ocaina.tendwise.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ocaina.tendwise.TestcontainersConfiguration;
import dev.ocaina.tendwise.auth.AccessTokens;
import dev.ocaina.tendwise.auth.AuthCookies;
import dev.ocaina.tendwise.auth.AuthenticatedUser;
import dev.ocaina.tendwise.auth.AuthenticatedUser.TokenMembership;
import dev.ocaina.tendwise.auth.SecureTokens;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.conversation.Channel;
import dev.ocaina.tendwise.conversation.Conversation;
import dev.ocaina.tendwise.conversation.ConversationService;
import dev.ocaina.tendwise.conversation.Message;
import dev.ocaina.tendwise.handoff.HandoffReason;
import dev.ocaina.tendwise.realtime.SseTestClient.Event;
import dev.ocaina.tendwise.user.MembershipRole;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The event bus and both SSE streams end to end (T40): a real server port, real HTTP streams,
 * PostgreSQL LISTEN/NOTIFY through Testcontainers.
 *
 * <p>Not {@code @Transactional}: the events are only sent when a transaction commits, so each test
 * commits (or rolls back) on purpose with a {@link TransactionTemplate}. Every test uses its own
 * new conversations, and the checks look at events about those conversations only.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "tendwise.api-key=test-key",
      // Short, so the heartbeat and the disconnect cleanup are seen quickly.
      "tendwise.realtime.heartbeat-interval=300ms",
      "tendwise.realtime.reconnect-delay=200ms"
    })
@Import(TestcontainersConfiguration.class)
class RealtimeStreamTest {

  private static final Duration WAIT = Duration.ofSeconds(5);
  private static final Duration QUIET = Duration.ofSeconds(1);

  @LocalServerPort private int port;
  @Autowired private ConversationService conversationService;
  @Autowired private BusinessRepository businesses;
  @Autowired private AccessTokens accessTokens;
  @Autowired private EventPublisher eventPublisher;
  @Autowired private EventStreams eventStreams;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  private long joesId;
  private long denimId;

  @BeforeEach
  void findBusinesses() {
    // No AI_REPLY job of these tests may run anywhere else.
    jdbc.update("DELETE FROM core.jobs");
    joesId = businesses.findBySlug("joes-pizza").orElseThrow().getId();
    denimId = businesses.findBySlug("denim-corner").orElseThrow().getId();
  }

  // --- Delivery after commit --------------------------------------------------------------------

  @Test
  void aMessageCommittedInOneTransactionReachesTheWidgetAndPanelStreams() throws Exception {
    WidgetConversation chat = startWidgetConversation(joesId);
    try (SseTestClient widget = openWidget(chat, Map.of());
        SseTestClient panel = openPanel("joes-pizza")) {

      Message message = commit(() -> customerSays(chat, "Do you deliver?"));

      Event widgetEvent = nextAbout(widget, chat.id());
      assertThat(widgetEvent.name()).isEqualTo("message.created");
      assertThat(widgetEvent.id()).isEqualTo(String.valueOf(message.getId()));
      JsonNode data = objectMapper.readTree(widgetEvent.data());
      assertThat(data.get("type").asText()).isEqualTo("message.created");
      assertThat(data.get("conversationId").asText()).isEqualTo(chat.id().toString());
      assertThat(data.get("id").asLong()).isEqualTo(message.getId());
      // Ids only: never the message text.
      assertThat(widgetEvent.data()).doesNotContain("deliver");

      Event panelEvent = nextAbout(panel, chat.id());
      assertThat(panelEvent.name()).isEqualTo("message.created");
      assertThat(panelEvent.id()).isEqualTo(String.valueOf(message.getId()));
    }
  }

  @Test
  void aRolledBackTransactionSendsNothing() throws Exception {
    WidgetConversation chat = startWidgetConversation(joesId);
    try (SseTestClient panel = openPanel("joes-pizza")) {

      transaction.executeWithoutResult(status -> {
        customerSays(chat, "This one is rolled back");
        status.setRollbackOnly();
      });

      assertThat(eventsAbout(panel, chat.id(), QUIET)).isEmpty();

      // The stream itself works: the next committed message arrives.
      Message kept = commit(() -> customerSays(chat, "This one is kept"));
      assertThat(nextAbout(panel, chat.id()).id()).isEqualTo(String.valueOf(kept.getId()));
    }
  }

  @Test
  void anotherBusinessesStreamsGetNothing() throws Exception {
    WidgetConversation joesChat = startWidgetConversation(joesId);
    WidgetConversation otherJoesChat = startWidgetConversation(joesId);
    try (SseTestClient joesPanel = openPanel("joes-pizza");
        SseTestClient denimPanel = openPanel("denim-corner");
        SseTestClient otherWidget = openWidget(otherJoesChat, Map.of())) {

      commit(() -> customerSays(joesChat, "Hello"));

      assertThat(nextAbout(joesPanel, joesChat.id()).name()).isEqualTo("message.created");
      // Another business's panel and another conversation's widget hear nothing about it.
      assertThat(eventsAbout(denimPanel, joesChat.id(), QUIET)).isEmpty();
      assertThat(eventsAbout(otherWidget, joesChat.id(), Duration.ofMillis(200))).isEmpty();
    }
  }

  @Test
  void theWidgetNeverGetsInternalMessagesOrPanelOnlyEvents() throws Exception {
    WidgetConversation chat = startWidgetConversation(joesId);
    try (SseTestClient widget = openWidget(chat, Map.of());
        SseTestClient panel = openPanel("joes-pizza")) {

      // HANDOFF_CREATED is an internal SYSTEM message (the handoff reason is for staff only).
      commit(() -> conversationService.handOff(chat.id(), joesId, HandoffReason.HUMAN_REQUEST, 1L));
      commit(() -> conversationService.takeOver(chat.id(), joesId, 42L));

      List<String> panelEvents =
          eventsAbout(panel, chat.id(), QUIET).stream().map(Event::name).toList();
      assertThat(panelEvents)
          .containsExactly(
              "message.created", // HANDOFF_CREATED (internal)
              "conversation.state_changed", // WAITING
              "message.created", // TAKEN_OVER
              "conversation.state_changed", // HUMAN
              "conversation.assigned");

      List<Event> widgetEvents = eventsAbout(widget, chat.id(), Duration.ofMillis(200));
      assertThat(widgetEvents)
          .extracting(Event::name)
          .containsExactly(
              "conversation.state_changed", "message.created", "conversation.state_changed");
      assertThat(objectMapper.readTree(widgetEvents.get(0).data()).get("state").asText())
          .isEqualTo("WAITING");
      assertThat(objectMapper.readTree(widgetEvents.get(2).data()).get("state").asText())
          .isEqualTo("HUMAN");
    }
  }

  @Test
  void aiTypingOutsideATransactionIsSentAtOnce() throws Exception {
    WidgetConversation chat = startWidgetConversation(joesId);
    try (SseTestClient widget = openWidget(chat, Map.of())) {

      eventPublisher.aiTyping(joesId, chat.id());

      assertThat(nextAbout(widget, chat.id()).name()).isEqualTo("ai.typing");
    }
  }

  // --- Who may open a stream --------------------------------------------------------------------

  @Test
  void theWidgetStreamWithAWrongOrMissingTokenIs404() throws Exception {
    WidgetConversation chat = startWidgetConversation(joesId);
    WidgetConversation other = startWidgetConversation(joesId);

    try (SseTestClient wrongToken =
            SseTestClient.open(
                widgetUrl(chat.id()), Map.of("Authorization", "Bearer " + other.token()));
        SseTestClient noToken = SseTestClient.open(widgetUrl(chat.id()), Map.of())) {
      assertThat(wrongToken.status()).isEqualTo(404);
      assertThat(noToken.status()).isEqualTo(404);
    }
  }

  @Test
  void theWidgetStreamFromAForeignOriginIs403() throws Exception {
    WidgetConversation chat = startWidgetConversation(joesId);

    try (SseTestClient stream =
        SseTestClient.open(
            widgetUrl(chat.id()),
            Map.of("Authorization", "Bearer " + chat.token(), "Origin", "https://evil.example"))) {
      assertThat(stream.status()).isEqualTo(403);
    }
  }

  @Test
  void thePanelStreamOfAForeignBusinessIs404AndNeedsALogin() throws Exception {
    String denimOwner = sessionFor("denim-corner");

    try (SseTestClient foreign =
            SseTestClient.open(
                panelUrl("joes-pizza"), Map.of("Cookie", AuthCookies.ACCESS + "=" + denimOwner));
        SseTestClient anonymous = SseTestClient.open(panelUrl("joes-pizza"), Map.of())) {
      assertThat(foreign.status()).isEqualTo(404);
      assertThat(anonymous.status()).isEqualTo(401);
    }
  }

  // --- Heartbeat, resume, cleanup, reconnect ----------------------------------------------------

  @Test
  void anOpenStreamGetsAHeartbeat() throws Exception {
    try (SseTestClient panel = openPanel("joes-pizza")) {
      assertThat(panel.nextComment(WAIT)).isEqualTo("connected");
      assertThat(panel.nextComment(WAIT)).isEqualTo("heartbeat");
    }
  }

  @Test
  void theWidgetStreamReplaysWhatTheCustomerMissedAfterLastEventId() throws Exception {
    WidgetConversation chat = startWidgetConversation(joesId);
    Message seen = commit(() -> customerSays(chat, "First"));
    Message missed = commit(() -> customerSays(chat, "Second"));
    // Internal: never replayed to the widget.
    commit(() -> conversationService.handOff(chat.id(), joesId, HandoffReason.HUMAN_REQUEST, 1L));

    try (SseTestClient widget =
        openWidget(chat, Map.of("Last-Event-ID", String.valueOf(seen.getId())))) {

      List<Event> replayed = eventsAbout(widget, chat.id(), QUIET);
      assertThat(replayed)
          .extracting(Event::name)
          .containsExactly("message.created", "conversation.state_changed");
      assertThat(replayed.get(0).id()).isEqualTo(String.valueOf(missed.getId()));
      assertThat(objectMapper.readTree(replayed.get(1).data()).get("state").asText())
          .isEqualTo("WAITING");
    }
  }

  @Test
  void thePanelStreamAsksForAResyncWhenItResumes() throws Exception {
    try (SseTestClient panel =
        SseTestClient.open(
            panelUrl("joes-pizza"),
            Map.of(
                "Cookie",
                AuthCookies.ACCESS + "=" + sessionFor("joes-pizza"),
                "Last-Event-ID",
                "123"))) {
      Event first = panel.next(WAIT);
      assertThat(first).isNotNull();
      assertThat(first.name()).isEqualTo("resync");
    }
  }

  @Test
  void aClosedStreamIsForgotten() throws Exception {
    // Every other test closed its streams; the heartbeat finds them gone.
    waitUntil(() -> eventStreams.openStreams() == 0);
    SseTestClient panel = openPanel("joes-pizza");
    assertThat(eventStreams.openStreams()).isEqualTo(1);

    panel.close();

    // The next heartbeat write fails (or the container reports the disconnect).
    waitUntil(() -> eventStreams.openStreams() == 0);
  }

  @Test
  void theListenerReconnectsAndStreamsAreToldToResync() throws Exception {
    WidgetConversation chat = startWidgetConversation(joesId);
    try (SseTestClient panel = openPanel("joes-pizza")) {

      // Kill the LISTEN connection, as a database restart would.
      Integer killed =
          jdbc.queryForObject(
              "SELECT count(pg_terminate_backend(pid))::int FROM pg_stat_activity"
                  + " WHERE application_name = ?",
              Integer.class,
              NotificationListener.APPLICATION_NAME);
      assertThat(killed).isEqualTo(1);

      Event resync = panel.next(WAIT);
      assertThat(resync).isNotNull();
      assertThat(resync.name()).isEqualTo("resync");

      Message message = commit(() -> customerSays(chat, "After the reconnect"));
      assertThat(nextAbout(panel, chat.id()).id()).isEqualTo(String.valueOf(message.getId()));
    }
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private record WidgetConversation(UUID id, String token) {}

  private WidgetConversation startWidgetConversation(long businessId) {
    String token = SecureTokens.generate();
    Conversation conversation =
        conversationService.start(
            businessId,
            Channel.WEB,
            "en",
            UUID.randomUUID().toString(),
            SecureTokens.sha256Hex(token));
    return new WidgetConversation(conversation.getId(), token);
  }

  private Message customerSays(WidgetConversation chat, String body) {
    return conversationService.addCustomerMessage(chat.id(), joesId, body);
  }

  private <T> T commit(Supplier<T> work) {
    return transaction.execute(status -> work.get());
  }

  private SseTestClient openWidget(WidgetConversation chat, Map<String, String> extraHeaders)
      throws Exception {
    Map<String, String> headers = new HashMap<>(extraHeaders);
    headers.put("Authorization", "Bearer " + chat.token());
    headers.put("Origin", "http://localhost:5173");
    SseTestClient client = SseTestClient.open(widgetUrl(chat.id()), headers);
    assertThat(client.status()).as(client.status() == 200 ? "" : client.errorBody()).isEqualTo(200);
    return client;
  }

  private SseTestClient openPanel(String slug) throws Exception {
    SseTestClient client =
        SseTestClient.open(
            panelUrl(slug), Map.of("Cookie", AuthCookies.ACCESS + "=" + sessionFor(slug)));
    assertThat(client.status()).as(client.status() == 200 ? "" : client.errorBody()).isEqualTo(200);
    return client;
  }

  private String sessionFor(String slug) {
    AuthenticatedUser user =
        new AuthenticatedUser(
            1_000_001L, null, List.of(new TokenMembership(slug, MembershipRole.AGENT)));
    return accessTokens.issue(user, Instant.now());
  }

  private String widgetUrl(UUID conversationId) {
    return "http://localhost:" + port + "/public/v1/conversations/" + conversationId + "/events";
  }

  private String panelUrl(String slug) {
    return "http://localhost:" + port + "/api/v1/panel/" + slug + "/events";
  }

  /** The next event about this conversation (skipping others); fails when none arrives. */
  private Event nextAbout(SseTestClient client, UUID conversationId) throws Exception {
    long end = System.nanoTime() + WAIT.toNanos();
    while (System.nanoTime() < end) {
      Event event = client.next(Duration.ofMillis(100));
      if (event != null && isAbout(event, conversationId)) {
        return event;
      }
    }
    throw new AssertionError("No event about conversation " + conversationId);
  }

  private List<Event> eventsAbout(SseTestClient client, UUID conversationId, Duration during)
      throws Exception {
    return client.collect(during).stream().filter(event -> isAbout(event, conversationId)).toList();
  }

  private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
    long end = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("Condition not met within " + WAIT);
      }
      Thread.sleep(50);
    }
  }

  private boolean isAbout(Event event, UUID conversationId) {
    try {
      JsonNode conversation = objectMapper.readTree(event.data()).get("conversationId");
      return conversation != null && conversation.asText().equals(conversationId.toString());
    } catch (Exception e) {
      return false;
    }
  }
}
