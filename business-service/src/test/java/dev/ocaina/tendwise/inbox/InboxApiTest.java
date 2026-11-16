package dev.ocaina.tendwise.inbox;

import static dev.ocaina.tendwise.panel.PanelRequests.tokenFor;
import static dev.ocaina.tendwise.panel.PanelRequests.withSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ocaina.tendwise.TestcontainersConfiguration;
import dev.ocaina.tendwise.auth.AccessTokens;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.conversation.Channel;
import dev.ocaina.tendwise.conversation.ConversationService;
import dev.ocaina.tendwise.handoff.CreateHandoffRequest;
import dev.ocaina.tendwise.handoff.HandoffReason;
import dev.ocaina.tendwise.handoff.HandoffService;
import dev.ocaina.tendwise.user.MembershipRole;
import dev.ocaina.tendwise.user.User;
import dev.ocaina.tendwise.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The inbox read API end to end: security chain, PanelGuard, controller, SQL and the seeded
 * PostgreSQL (joes-pizza and denim-corner).
 *
 * <p>{@code @Transactional} rolls back every test. Conversations committed by other test classes
 * (the job tests) are deleted at the start, inside the same transaction, so the counts only see
 * this test's data.
 */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class InboxApiTest {

  private static final String JOES = "joes-pizza";
  private static final String LIST = "/api/v1/panel/{slug}/conversations";
  private static final String DETAIL = "/api/v1/panel/{slug}/conversations/{id}";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AccessTokens accessTokens;
  @Autowired private BusinessRepository businesses;
  @Autowired private ConversationService conversationService;
  @Autowired private HandoffService handoffService;
  @Autowired private UserRepository users;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  private long joesId;
  private long denimId;
  private User me;
  private User colleague;
  private String myToken;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM core.messages");
    jdbc.update("DELETE FROM core.handoffs");
    jdbc.update("DELETE FROM core.conversations");
    joesId = businesses.findBySlug(JOES).orElseThrow().getId();
    denimId = businesses.findBySlug("denim-corner").orElseThrow().getId();
    me = users.save(new User("maria@inbox.test", "not-a-real-hash", "Maria", null));
    colleague = users.save(new User("joao@inbox.test", "not-a-real-hash", "João", null));
    myToken = tokenFor(accessTokens, me.getId(), JOES, MembershipRole.AGENT);
  }

  // --- Tabs ------------------------------------------------------------------------------------

  @Test
  void eachTabListsItsConversations() throws Exception {
    UUID ai = conversation(joesId);
    UUID waiting = handedOff(conversation(joesId), HandoffReason.COMPLAINT);
    UUID mine = takenBy(handedOff(conversation(joesId), HandoffReason.HUMAN_REQUEST), me);
    UUID colleagues =
        takenBy(handedOff(conversation(joesId), HandoffReason.HUMAN_REQUEST), colleague);
    UUID resolved = conversation(joesId);
    conversationService.resolve(resolved, joesId, me.getId());

    assertThat(idsOf(list("state=WAITING"))).containsExactly(waiting);
    assertThat(idsOf(list("state=HUMAN&assignee=me"))).containsExactly(mine);
    assertThat(idsOf(list("state=HUMAN&assignee=" + colleague.getId())))
        .containsExactly(colleagues);
    assertThat(idsOf(list("state=AI"))).containsExactly(ai);
    assertThat(idsOf(list("state=RESOLVED"))).containsExactly(resolved);
    assertThat(idsOf(list(""))).containsExactlyInAnyOrder(ai, waiting, mine, colleagues, resolved);
  }

  @Test
  void waitingItemShowsHandoffReasonAndWaitingSince() throws Exception {
    UUID waiting = handedOff(conversation(joesId), HandoffReason.COMPLAINT);

    JsonNode item = list("state=WAITING").get("items").get(0);

    assertThat(item.get("id").asText()).isEqualTo(waiting.toString());
    assertThat(item.get("state").asText()).isEqualTo("WAITING");
    assertThat(item.get("channel").asText()).isEqualTo("WEB");
    assertThat(item.get("language").asText()).isEqualTo("pt");
    assertThat(item.get("handoffReason").asText()).isEqualTo("COMPLAINT");
    assertThat(item.get("waitingSince").isNull()).isFalse();
    assertThat(item.get("assignee").isNull()).isTrue();
  }

  @Test
  void takenConversationShowsAssigneeAndNoWaitingTime() throws Exception {
    takenBy(handedOff(conversation(joesId), HandoffReason.HUMAN_REQUEST), me);

    JsonNode item = list("state=HUMAN&assignee=me").get("items").get(0);

    assertThat(item.get("assignee").get("id").asLong()).isEqualTo(me.getId());
    assertThat(item.get("assignee").get("name").asText()).isEqualTo("Maria");
    // The handoff is IN_PROGRESS, so its reason is still shown; the waiting timer stops.
    assertThat(item.get("handoffReason").asText()).isEqualTo("HUMAN_REQUEST");
    assertThat(item.get("waitingSince").isNull()).isTrue();
  }

  @Test
  void itemShowsAShortPreviewOfTheLastNonSystemMessageAndTheUnreadCount() throws Exception {
    UUID id = conversation(joesId);
    conversationService.addCustomerMessage(id, joesId, "Oi");
    conversationService.addCustomerMessage(id, joesId, "x".repeat(500));
    handedOff(id, HandoffReason.COMPLAINT);

    JsonNode item = list("").get("items").get(0);

    // The HANDOFF_CREATED SYSTEM message is newer, but the preview skips SYSTEM messages.
    assertThat(item.get("lastMessage").get("sender").asText()).isEqualTo("CUSTOMER");
    assertThat(item.get("lastMessage").get("preview").asText())
        .isEqualTo("x".repeat(InboxQueries.PREVIEW_LENGTH));
    assertThat(item.get("lastMessage").get("internal").asBoolean()).isFalse();
    assertThat(item.get("unreadCount").asInt()).isEqualTo(2);
  }

  @Test
  void conversationWithoutMessagesHasNoPreview() throws Exception {
    conversation(joesId);

    JsonNode item = list("").get("items").get(0);

    assertThat(item.get("lastMessage").isNull()).isTrue();
    assertThat(item.get("unreadCount").asInt()).isZero();
  }

  // --- Counts ----------------------------------------------------------------------------------

  @Test
  void countsGiveEachTabItsBadge() throws Exception {
    UUID waitingUnread = conversation(joesId);
    conversationService.addCustomerMessage(waitingUnread, joesId, "Preciso de ajuda");
    handedOff(waitingUnread, HandoffReason.COMPLAINT);
    handedOff(conversation(joesId), HandoffReason.COMPLAINT);
    UUID mineUnread = handedOff(conversation(joesId), HandoffReason.HUMAN_REQUEST);
    takenBy(mineUnread, me);
    conversationService.addCustomerMessage(mineUnread, joesId, "Obrigado");
    takenBy(handedOff(conversation(joesId), HandoffReason.HUMAN_REQUEST), colleague);
    conversation(joesId);
    conversation(joesId);
    conversationService.resolve(conversation(joesId), joesId, me.getId());
    // Another business never counts.
    handedOff(conversation(denimId), HandoffReason.COMPLAINT, "denim-corner");

    // Counts ignore the filters: the same on every tab and page.
    JsonNode counts = list("state=RESOLVED").get("counts");

    assertThat(counts.get("waiting").get("conversations").asLong()).isEqualTo(2);
    assertThat(counts.get("waiting").get("unread").asLong()).isEqualTo(1);
    assertThat(counts.get("mine").get("conversations").asLong()).isEqualTo(1);
    assertThat(counts.get("mine").get("unread").asLong()).isEqualTo(1);
    assertThat(counts.get("ai").get("conversations").asLong()).isEqualTo(2);
    assertThat(counts.get("ai").get("unread").asLong()).isZero();
    assertThat(counts.has("resolved")).isFalse();
  }

  // --- Pagination ------------------------------------------------------------------------------

  @Test
  void pagesAreNewestFirstAndStableWithEqualTimes() throws Exception {
    Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      UUID id = conversation(joesId);
      // Three conversations share the newest time: the id breaks the tie.
      setLastMessageAt(id, i < 3 ? base : base.minusSeconds(i));
      ids.add(id);
    }
    List<UUID> expected =
        List.of(
            sortedByIdDesc(ids.subList(0, 3)).get(0),
            sortedByIdDesc(ids.subList(0, 3)).get(1),
            sortedByIdDesc(ids.subList(0, 3)).get(2),
            ids.get(3),
            ids.get(4));

    List<UUID> seen = new ArrayList<>();
    JsonNode page = list("limit=2");
    int pages = 1;
    seen.addAll(idsOf(page));
    while (!page.get("nextCursor").isNull()) {
      assertThat(page.get("items")).hasSize(2);
      page = list("limit=2&cursor=" + page.get("nextCursor").asText());
      seen.addAll(idsOf(page));
      pages++;
    }

    assertThat(pages).isEqualTo(3);
    assertThat(seen).containsExactlyElementsOf(expected);
  }

  @Test
  void lastFullPageHasNoNextCursor() throws Exception {
    conversation(joesId);
    conversation(joesId);

    JsonNode page = list("limit=2");

    assertThat(page.get("items")).hasSize(2);
    assertThat(page.get("nextCursor").isNull()).isTrue();
  }

  @Test
  void badParametersAre400() throws Exception {
    listRequest("cursor=not-a-cursor").andExpect(status().isBadRequest());
    listRequest("assignee=somebody").andExpect(status().isBadRequest());
    listRequest("limit=0").andExpect(status().isBadRequest());
    listRequest("limit=101").andExpect(status().isBadRequest());
    listRequest("state=SLEEPING").andExpect(status().isBadRequest());
    listRequest("q=" + "a".repeat(201)).andExpect(status().isBadRequest());
  }

  // --- Search ----------------------------------------------------------------------------------

  @Test
  void searchFindsWordsInMessagesIgnoringCase() throws Exception {
    UUID pizza = conversation(joesId);
    conversationService.addCustomerMessage(pizza, joesId, "Vocês entregam PIZZA no centro?");
    UUID other = conversation(joesId);
    conversationService.addCustomerMessage(other, joesId, "Qual o horário?");

    assertThat(idsOf(list("q=pizza"))).containsExactly(pizza);
    assertThat(idsOf(list("q=nada-disso"))).isEmpty();
  }

  @Test
  void searchTreatsLikeWildcardsAsText() throws Exception {
    UUID discount = conversation(joesId);
    conversationService.addCustomerMessage(discount, joesId, "Tem 50% de desconto?");
    UUID other = conversation(joesId);
    conversationService.addCustomerMessage(other, joesId, "Tem 500 sabores?");

    assertThat(idsOf(list("q=50%"))).containsExactly(discount);
  }

  // --- Detail ----------------------------------------------------------------------------------

  @Test
  void detailHasEveryMessageIncludingInternalNotesAndTheHandoffs() throws Exception {
    UUID id = conversation(joesId);
    conversationService.addCustomerMessage(id, joesId, "Meu pedido veio errado");
    conversationService.addAiMessage(
        id,
        joesId,
        "Sinto muito! Vou chamar a equipe.",
        Map.of(
            "intent",
            "complaint",
            "sources",
            List.of(Map.of("title", "Trocas", "source_path", "joes-pizza/policies.md"))));
    handedOff(id, HandoffReason.COMPLAINT);
    takenBy(id, me);
    // Staff replies and internal notes come with T44: inserted directly here.
    insertHumanMessage(id, me.getId(), "Olá, sou a Maria.", false);
    insertHumanMessage(id, colleague.getId(), "Cliente antigo, dar desconto.", true);

    mockMvc
        .perform(withSession(get(DETAIL, JOES, id), myToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.state").value("HUMAN"))
        .andExpect(jsonPath("$.assignee.name").value("Maria"))
        .andExpect(jsonPath("$.waitingSince").value(nullValue()))
        // CUSTOMER, AI, HANDOFF_CREATED, TAKEN_OVER, HUMAN, HUMAN internal note.
        .andExpect(jsonPath("$.messages", hasSize(6)))
        .andExpect(jsonPath("$.messages[0].sender").value("CUSTOMER"))
        .andExpect(jsonPath("$.messages[1].sender").value("AI"))
        .andExpect(jsonPath("$.messages[1].metadata.intent").value("complaint"))
        .andExpect(jsonPath("$.messages[1].metadata.sources[0].title").value("Trocas"))
        .andExpect(jsonPath("$.messages[2].sender").value("SYSTEM"))
        .andExpect(jsonPath("$.messages[2].internal").value(true))
        .andExpect(jsonPath("$.messages[2].metadata.event").value("HANDOFF_CREATED"))
        .andExpect(jsonPath("$.messages[4].authorName").value("Maria"))
        .andExpect(jsonPath("$.messages[4].internal").value(false))
        .andExpect(jsonPath("$.messages[5].body").value("Cliente antigo, dar desconto."))
        .andExpect(jsonPath("$.messages[5].internal").value(true))
        .andExpect(jsonPath("$.messages[5].authorUserId").value(colleague.getId()))
        .andExpect(jsonPath("$.messages[5].authorName").value("João"))
        .andExpect(jsonPath("$.handoffs", hasSize(1)))
        .andExpect(jsonPath("$.handoffs[0].reason").value("COMPLAINT"))
        .andExpect(jsonPath("$.handoffs[0].summary").value("Resumo da IA"))
        .andExpect(jsonPath("$.handoffs[0].status").value("IN_PROGRESS"));
  }

  @Test
  void waitingDetailHasWaitingSince() throws Exception {
    UUID id = handedOff(conversation(joesId), HandoffReason.COMPLAINT);

    mockMvc
        .perform(withSession(get(DETAIL, JOES, id), myToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"))
        .andExpect(jsonPath("$.waitingSince").isString())
        .andExpect(jsonPath("$.assignee").value(nullValue()));
  }

  // --- Tenant isolation and roles --------------------------------------------------------------

  @Test
  void conversationOfAnotherBusinessIs404() throws Exception {
    UUID denims = conversation(denimId);

    mockMvc
        .perform(withSession(get(DETAIL, JOES, denims), myToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Conversation not found"));
    mockMvc
        .perform(withSession(get(DETAIL, JOES, UUID.randomUUID()), myToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Conversation not found"));
  }

  @Test
  void listNeverShowsAnotherBusinessesConversations() throws Exception {
    UUID joes = conversation(joesId);
    UUID denims = conversation(denimId);
    conversationService.addCustomerMessage(denims, denimId, "pizza");

    assertThat(idsOf(list(""))).containsExactly(joes);
    assertThat(idsOf(list("q=pizza"))).isEmpty();
  }

  @Test
  void agentAndOwnerCanRead() throws Exception {
    UUID id = conversation(joesId);
    for (MembershipRole role : MembershipRole.values()) {
      String token = tokenFor(accessTokens, me.getId(), JOES, role);
      mockMvc.perform(withSession(get(LIST, JOES), token)).andExpect(status().isOk());
      mockMvc.perform(withSession(get(DETAIL, JOES, id), token)).andExpect(status().isOk());
    }
  }

  // --- Helpers ---------------------------------------------------------------------------------

  /**
   * A new conversation in AI. Flushed, so the list's plain SQL (which does not flush JPA) and the
   * test's own JDBC updates see it.
   */
  private UUID conversation(long businessId) {
    UUID id =
        conversationService
            .start(businessId, Channel.WEB, "pt", "ref-" + UUID.randomUUID(), null)
            .getId();
    entityManager.flush();
    return id;
  }

  private UUID handedOff(UUID id, HandoffReason reason) {
    return handedOff(id, reason, JOES);
  }

  private UUID handedOff(UUID id, HandoffReason reason, String slug) {
    handoffService.create(new CreateHandoffRequest(slug, id, reason, "Resumo da IA"));
    return id;
  }

  private UUID takenBy(UUID id, User user) {
    conversationService.takeOver(id, joesId, user.getId());
    return id;
  }

  private void setLastMessageAt(UUID id, Instant at) {
    jdbc.update(
        "UPDATE core.conversations SET last_message_at = ? WHERE id = ?", Timestamp.from(at), id);
  }

  private void insertHumanMessage(UUID id, long authorUserId, String body, boolean internal) {
    jdbc.update(
        """
        INSERT INTO core.messages
            (conversation_id, business_id, sender, author_user_id, body, internal, created_at)
        VALUES (?, ?, 'HUMAN', ?, ?, ?, now())
        """,
        id,
        joesId,
        authorUserId,
        body,
        internal);
  }

  private static List<UUID> sortedByIdDesc(List<UUID> ids) {
    // PostgreSQL compares UUIDs byte by byte, like their lowercase text.
    return ids.stream().sorted(Comparator.comparing(UUID::toString).reversed()).toList();
  }

  /** query: "name=value&name=value", values not URL-encoded (e.g. "q=50%"). */
  private ResultActions listRequest(String query) throws Exception {
    MockHttpServletRequestBuilder request = get(LIST, JOES);
    for (String pair : query.split("&")) {
      if (!pair.isEmpty()) {
        int equals = pair.indexOf('=');
        request = request.param(pair.substring(0, equals), pair.substring(equals + 1));
      }
    }
    return mockMvc.perform(withSession(request, myToken));
  }

  private JsonNode list(String query) throws Exception {
    String body =
        listRequest(query)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body);
  }

  private static List<UUID> idsOf(JsonNode page) {
    List<UUID> ids = new ArrayList<>();
    page.get("items").forEach(item -> ids.add(UUID.fromString(item.get("id").asText())));
    return ids;
  }
}
