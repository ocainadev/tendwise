package dev.ocaina.tendwise.inbox;

import static dev.ocaina.tendwise.panel.PanelRequests.tokenFor;
import static dev.ocaina.tendwise.panel.PanelRequests.withSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ocaina.tendwise.TestcontainersConfiguration;
import dev.ocaina.tendwise.audit.AuditEntry;
import dev.ocaina.tendwise.audit.AuditLogRepository;
import dev.ocaina.tendwise.auth.AccessTokens;
import dev.ocaina.tendwise.auth.SecureTokens;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.conversation.Channel;
import dev.ocaina.tendwise.conversation.ConversationService;
import dev.ocaina.tendwise.handoff.CreateHandoffRequest;
import dev.ocaina.tendwise.handoff.Handoff;
import dev.ocaina.tendwise.handoff.HandoffReason;
import dev.ocaina.tendwise.handoff.HandoffRepository;
import dev.ocaina.tendwise.handoff.HandoffService;
import dev.ocaina.tendwise.handoff.HandoffStatus;
import dev.ocaina.tendwise.job.JobRepository;
import dev.ocaina.tendwise.job.JobType;
import dev.ocaina.tendwise.user.Membership;
import dev.ocaina.tendwise.user.MembershipRepository;
import dev.ocaina.tendwise.user.MembershipRole;
import dev.ocaina.tendwise.user.User;
import dev.ocaina.tendwise.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * The inbox actions (T44) end to end through MockMvc: security chain, PanelGuard, controller,
 * ConversationService and PostgreSQL. {@code @Transactional} rolls every test back; what needs real
 * commits (the takeover race, the live streams) is in {@link InboxActionLiveTest}.
 */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class InboxActionApiTest {

  private static final String JOES = "joes-pizza";
  private static final String ACTION = "/api/v1/panel/{slug}/conversations/{id}/{action}";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AccessTokens accessTokens;
  @Autowired private BusinessRepository businesses;
  @Autowired private ConversationService conversationService;
  @Autowired private HandoffService handoffService;
  @Autowired private HandoffRepository handoffs;
  @Autowired private AuditLogRepository auditLog;
  @Autowired private JobRepository jobs;
  @Autowired private UserRepository users;
  @Autowired private MembershipRepository memberships;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  private long joesId;
  private long denimId;
  private User maria;
  private User joao;
  private String mariaToken;
  private String joaoToken;

  @BeforeEach
  void setUp() {
    joesId = businesses.findBySlug(JOES).orElseThrow().getId();
    denimId = businesses.findBySlug("denim-corner").orElseThrow().getId();
    maria = member("Maria Souza");
    joao = member("João Lima");
    mariaToken = tokenFor(accessTokens, maria.getId(), JOES, MembershipRole.AGENT);
    joaoToken = tokenFor(accessTokens, joao.getId(), JOES, MembershipRole.AGENT);
  }

  // --- Take over ------------------------------------------------------------------------------

  @Test
  void takeOverMovesWaitingToHumanAndAssignsTheHandoff() throws Exception {
    UUID id = waiting();

    JsonNode body = json(action(id, "takeover", mariaToken).andExpect(status().isOk()));

    assertThat(body.get("state").asText()).isEqualTo("HUMAN");
    assertThat(body.get("assignee").get("id").asLong()).isEqualTo(maria.getId());
    assertThat(body.get("assignee").get("name").asText()).isEqualTo("Maria Souza");
    JsonNode system = body.get("messages").get(body.get("messages").size() - 1);
    assertThat(system.get("sender").asText()).isEqualTo("SYSTEM");
    assertThat(system.get("internal").asBoolean()).isFalse();
    assertThat(system.get("metadata").get("event").asText()).isEqualTo("TAKEN_OVER");
    assertThat(system.get("metadata").get("userId").asLong()).isEqualTo(maria.getId());

    Handoff handoff = onlyHandoff(id);
    assertThat(handoff.getStatus()).isEqualTo(HandoffStatus.IN_PROGRESS);
    assertThat(handoff.getAssigneeUserId()).isEqualTo(maria.getId());
    assertThat(handoff.getAssignedAt()).isNotNull();
    assertThat(audit(id))
        .extracting(AuditEntry::getAction)
        .containsExactly("conversation.takeover");
    assertThat(audit(id).getFirst().getActorUserId()).isEqualTo(maria.getId());
  }

  @Test
  void takeOverWorksFromAiToo() throws Exception {
    UUID id = conversation(joesId);

    action(id, "takeover", mariaToken)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("HUMAN"));
  }

  @Test
  void takingOverAConversationSomeoneElseHasIs409WithTheirName() throws Exception {
    UUID id = waiting();
    action(id, "takeover", mariaToken).andExpect(status().isOk());

    action(id, "takeover", joaoToken)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.state").value("HUMAN"))
        .andExpect(jsonPath("$.assigneeUserId").value(maria.getId()))
        .andExpect(jsonPath("$.assigneeName").value("Maria Souza"))
        .andExpect(jsonPath("$.detail").value("Maria Souza already took this conversation."));
  }

  @Test
  void takingOverOwnConversationAgainIsFine() throws Exception {
    UUID id = waiting();
    action(id, "takeover", mariaToken).andExpect(status().isOk());

    action(id, "takeover", mariaToken).andExpect(status().isOk());

    assertThat(systemEvents(id)).containsExactly("HANDOFF_CREATED", "TAKEN_OVER");
  }

  @Test
  void takingOverAResolvedConversationIs409() throws Exception {
    UUID id = conversation(joesId);
    conversationService.resolve(id, joesId, maria.getId());

    action(id, "takeover", mariaToken)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.state").value("RESOLVED"));
  }

  // --- Messages -------------------------------------------------------------------------------

  @Test
  void theAssigneeRepliesToTheCustomer() throws Exception {
    UUID id = takenBy(maria);

    JsonNode message =
        json(send(id, mariaToken, "Olá! Já vou verificar.", false).andExpect(status().isCreated()));

    assertThat(message.get("sender").asText()).isEqualTo("HUMAN");
    assertThat(message.get("authorUserId").asLong()).isEqualTo(maria.getId());
    assertThat(message.get("authorName").asText()).isEqualTo("Maria Souza");
    assertThat(message.get("body").asText()).isEqualTo("Olá! Já vou verificar.");
    assertThat(message.get("internal").asBoolean()).isFalse();
    // A staff message never queues an AI reply.
    assertThat(jobs.findAll())
        .noneMatch(job ->
            job.getType() == JobType.AI_REPLY
                && id.toString().equals(job.getPayload().get("conversationId")));
  }

  @Test
  void firstResponseAtIsSetOnceByTheFirstPublicReply() throws Exception {
    UUID id = takenBy(maria);
    send(id, mariaToken, "Nota interna", true).andExpect(status().isCreated());
    assertThat(onlyHandoff(id).getFirstResponseAt()).isNull();

    send(id, mariaToken, "Primeira resposta", false).andExpect(status().isCreated());
    var first = onlyHandoff(id).getFirstResponseAt();
    send(id, mariaToken, "Segunda resposta", false).andExpect(status().isCreated());

    assertThat(first).isNotNull();
    assertThat(onlyHandoff(id).getFirstResponseAt()).isEqualTo(first);
  }

  @Test
  void someoneWhoIsNotTheAssigneeCannotReplyToTheCustomer() throws Exception {
    UUID id = takenBy(maria);

    send(id, joaoToken, "Oi", false)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Not the assignee"))
        .andExpect(jsonPath("$.assigneeName").value("Maria Souza"));
  }

  @Test
  void aReplyBeforeAnyoneTookTheConversationIs409() throws Exception {
    UUID id = waiting();

    send(id, mariaToken, "Oi", false)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.state").value("WAITING"));
  }

  @Test
  void anyMemberWritesInternalNotesInAnyState() throws Exception {
    UUID id = takenBy(maria);
    UUID ai = conversation(joesId);

    send(id, joaoToken, "Cliente antigo", true)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.internal").value(true));
    send(ai, joaoToken, "Ficar de olho", true).andExpect(status().isCreated());
  }

  @Test
  void internalNotesNeverReachThePublicHistory() throws Exception {
    String token = SecureTokens.generate();
    UUID id =
        conversationService
            .start(joesId, Channel.WEB, "pt", "ref-" + UUID.randomUUID(), sha(token))
            .getId();
    action(id, "takeover", mariaToken).andExpect(status().isOk());
    send(id, mariaToken, "Segredo da equipe", true).andExpect(status().isCreated());
    send(id, mariaToken, "Oi, sou a Maria", false).andExpect(status().isCreated());

    String history =
        mockMvc
            .perform(
                get("/public/v1/conversations/{id}/messages", id)
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(history).contains("Oi, sou a Maria").doesNotContain("Segredo da equipe");
    // The customer sees the first name only.
    assertThat(history).contains("\"authorName\":\"Maria\"").doesNotContain("Souza");
  }

  @Test
  void messageBodyHasThePublicApiLimits() throws Exception {
    UUID id = takenBy(maria);

    send(id, mariaToken, "", false).andExpect(status().isBadRequest());
    send(id, mariaToken, "a".repeat(4001), false).andExpect(status().isBadRequest());
    send(id, mariaToken, "a".repeat(4000), false).andExpect(status().isCreated());
  }

  // --- Assign ---------------------------------------------------------------------------------

  @Test
  void assignGivesTheConversationToAColleague() throws Exception {
    UUID id = takenBy(maria);

    JsonNode body =
        json(
            postJson(id, "assign", mariaToken, "{\"userId\": " + joao.getId() + "}")
                .andExpect(status().isOk()));

    assertThat(body.get("state").asText()).isEqualTo("HUMAN");
    assertThat(body.get("assignee").get("id").asLong()).isEqualTo(joao.getId());
    JsonNode system = body.get("messages").get(body.get("messages").size() - 1);
    assertThat(system.get("metadata").get("event").asText()).isEqualTo("ASSIGNED");
    assertThat(system.get("internal").asBoolean()).isTrue();
    assertThat(onlyHandoff(id).getAssigneeUserId()).isEqualTo(joao.getId());
    // Now only João may reply.
    send(id, mariaToken, "Oi", false).andExpect(status().isForbidden());
    send(id, joaoToken, "Oi", false).andExpect(status().isCreated());
    assertThat(audit(id)).extracting(AuditEntry::getAction).containsExactly("conversation.assign");
    assertThat(audit(id).getFirst().getMetadata())
        .containsEntry("assigneeUserId", joao.getId().intValue());
  }

  @Test
  void assignAWaitingConversationTellsTheCustomerSomeoneJoined() throws Exception {
    UUID id = waiting();

    postJson(id, "assign", mariaToken, "{\"userId\": " + joao.getId() + "}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignee.id").value(joao.getId()));

    assertThat(systemEvents(id)).containsExactly("HANDOFF_CREATED", "TAKEN_OVER");
    assertThat(onlyHandoff(id).getStatus()).isEqualTo(HandoffStatus.IN_PROGRESS);
  }

  @Test
  void assignToSomeoneWhoIsNotAMemberIs400() throws Exception {
    UUID id = waiting();
    User outsider = users.save(new User(email(), "not-a-real-hash", "Outsider", null));
    memberships.save(new Membership(outsider.getId(), denimId, MembershipRole.AGENT));

    postJson(id, "assign", mariaToken, "{\"userId\": " + outsider.getId() + "}")
        .andExpect(status().isBadRequest());
    postJson(id, "assign", mariaToken, "{}").andExpect(status().isBadRequest());
  }

  // --- Release, resolve, read -----------------------------------------------------------------

  @Test
  void releaseHandsBackToTheAiAndEndsTheHandoff() throws Exception {
    UUID id = takenBy(maria);

    JsonNode body = json(action(id, "release", mariaToken).andExpect(status().isOk()));

    assertThat(body.get("state").asText()).isEqualTo("AI");
    assertThat(body.get("assignee").isNull()).isTrue();
    JsonNode system = body.get("messages").get(body.get("messages").size() - 1);
    assertThat(system.get("metadata").get("event").asText()).isEqualTo("RELEASED");
    assertThat(system.get("internal").asBoolean()).isFalse();
    Handoff handoff = onlyHandoff(id);
    assertThat(handoff.getStatus()).isEqualTo(HandoffStatus.RESOLVED);
    assertThat(handoff.getResolvedAt()).isNotNull();
    assertThat(handoff.getResolvedBy()).isEqualTo(maria.getId());
    // Release queues nothing by itself: the next customer message does.
    assertThat(jobs.findAll())
        .noneMatch(job ->
            job.getType() == JobType.AI_REPLY
                && id.toString().equals(job.getPayload().get("conversationId")));
    action(id, "release", mariaToken).andExpect(status().isConflict());
  }

  @Test
  void resolveWorksFromEveryOpenStateAndTheCustomerReopensIt() throws Exception {
    UUID ai = conversation(joesId);
    UUID waiting = waiting();
    UUID human = takenBy(maria);

    for (UUID id : List.of(ai, waiting, human)) {
      action(id, "resolve", joaoToken)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.state").value("RESOLVED"))
          .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
    }
    Handoff handoff = onlyHandoff(human);
    assertThat(handoff.getStatus()).isEqualTo(HandoffStatus.RESOLVED);
    assertThat(handoff.getResolvedBy()).isEqualTo(joao.getId());
    action(human, "resolve", joaoToken).andExpect(status().isConflict());

    conversationService.addCustomerMessage(human, joesId, "Mais uma pergunta");
    assertThat(systemEvents(human)).endsWith("RESOLVED", "REOPENED");
    assertThat(stateOf(human)).isEqualTo("AI");
  }

  @Test
  void readClearsTheUnreadCount() throws Exception {
    UUID id = waiting();
    conversationService.addCustomerMessage(id, joesId, "Alguém aí?");
    conversationService.addCustomerMessage(id, joesId, "Oi?");
    assertThat(unreadOf(id)).isEqualTo(2);

    action(id, "read", mariaToken).andExpect(status().isNoContent());

    assertThat(unreadOf(id)).isZero();
  }

  @Test
  void typingIsOnlyForTheAssignee() throws Exception {
    UUID id = takenBy(maria);
    UUID waiting = waiting();

    action(id, "typing", mariaToken).andExpect(status().isNoContent());
    action(id, "typing", joaoToken).andExpect(status().isForbidden());
    action(waiting, "typing", mariaToken).andExpect(status().isConflict());
  }

  // --- Tenant isolation -----------------------------------------------------------------------

  @Test
  void aConversationOfAnotherBusinessIs404ForEveryAction() throws Exception {
    UUID denims = conversation(denimId);

    for (String action : List.of("takeover", "release", "resolve", "read", "typing")) {
      action(denims, action, mariaToken).andExpect(status().isNotFound());
    }
    send(denims, mariaToken, "Oi", true).andExpect(status().isNotFound());
    postJson(denims, "assign", mariaToken, "{\"userId\": " + joao.getId() + "}")
        .andExpect(status().isNotFound());
    assertThat(stateOf(denims)).isEqualTo("AI");
    assertThat(systemEvents(denims)).isEmpty();
  }

  // --- helpers --------------------------------------------------------------------------------

  private User member(String name) {
    User user = users.save(new User(email(), "not-a-real-hash", name, null));
    memberships.save(new Membership(user.getId(), joesId, MembershipRole.AGENT));
    return user;
  }

  private static String email() {
    return "staff-" + UUID.randomUUID() + "@actions.test";
  }

  private UUID conversation(long businessId) {
    UUID id =
        conversationService
            .start(businessId, Channel.WEB, "pt", "ref-" + UUID.randomUUID(), null)
            .getId();
    entityManager.flush();
    return id;
  }

  private UUID waiting() {
    UUID id = conversation(joesId);
    handoffService.create(
        new CreateHandoffRequest(JOES, id, HandoffReason.HUMAN_REQUEST, "Resumo da IA"));
    return id;
  }

  private UUID takenBy(User user) {
    UUID id = waiting();
    conversationService.takeOver(id, joesId, user.getId());
    return id;
  }

  private ResultActions action(UUID id, String action, String token) throws Exception {
    return mockMvc.perform(withSession(post(ACTION, JOES, id, action), token));
  }

  private ResultActions postJson(UUID id, String action, String token, String body)
      throws Exception {
    return mockMvc.perform(
        withSession(post(ACTION, JOES, id, action), token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions send(UUID id, String token, String body, boolean internal)
      throws Exception {
    String json = objectMapper.writeValueAsString(Map.of("body", body, "internal", internal));
    return postJson(id, "messages", token, json);
  }

  private JsonNode json(ResultActions result) throws Exception {
    return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private Handoff onlyHandoff(UUID id) {
    entityManager.clear();
    List<Handoff> found = handoffs.findByBusinessIdAndConversationIdOrderByCreatedAtAsc(joesId, id);
    assertThat(found).hasSize(1);
    return found.getFirst();
  }

  private List<AuditEntry> audit(UUID id) {
    return auditLog.findByBusinessIdAndTargetTypeAndTargetIdOrderByIdAsc(
        joesId, "conversation", id.toString());
  }

  private List<String> systemEvents(UUID id) {
    return jdbc.queryForList(
        """
        SELECT metadata ->> 'event' FROM core.messages
        WHERE conversation_id = ? AND sender = 'SYSTEM' ORDER BY id
        """,
        String.class,
        id);
  }

  private String stateOf(UUID id) {
    return jdbc.queryForObject(
        "SELECT state FROM core.conversations WHERE id = ?", String.class, id);
  }

  private int unreadOf(UUID id) {
    return jdbc.queryForObject(
        "SELECT unread_by_staff FROM core.conversations WHERE id = ?", Integer.class, id);
  }

  private static String sha(String token) {
    return SecureTokens.sha256Hex(token);
  }
}
