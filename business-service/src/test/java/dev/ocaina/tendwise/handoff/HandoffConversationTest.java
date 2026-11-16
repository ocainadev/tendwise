package dev.ocaina.tendwise.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ocaina.tendwise.TestcontainersConfiguration;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.conversation.Channel;
import dev.ocaina.tendwise.conversation.Conversation;
import dev.ocaina.tendwise.conversation.ConversationRepository;
import dev.ocaina.tendwise.conversation.ConversationService;
import dev.ocaina.tendwise.conversation.ConversationState;
import dev.ocaina.tendwise.conversation.Message;
import dev.ocaina.tendwise.conversation.MessageRepository;
import dev.ocaina.tendwise.conversation.MessageSender;
import dev.ocaina.tendwise.job.Job;
import dev.ocaina.tendwise.job.JobRepository;
import dev.ocaina.tendwise.job.JobType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * T41: a handoff moves its conversation to WAITING, the handoff status follows the conversation,
 * and the AUTO_ACK job sends one automatic message.
 *
 * <p>The seeded businesses have real opening hours, so whether they are open depends on when the
 * test runs. Each test inserts its own businesses instead: "open-shop" has no hours set (unknown
 * hours count as always open) and "closed-shop" is closed every day. {@code @Transactional} rolls
 * everything back after each test.
 */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class HandoffConversationTest {

  private static final long STAFF_ID = 7L;
  private static final String ALWAYS_CLOSED =
      """
      {"mon": [], "tue": [], "wed": [], "thu": [], "fri": [], "sat": [], "sun": []}\
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private BusinessRepository businesses;
  @Autowired private ConversationService conversationService;
  @Autowired private ConversationRepository conversations;
  @Autowired private MessageRepository messages;
  @Autowired private HandoffRepository handoffs;
  @Autowired private JobRepository jobs;
  @Autowired private AutoAckJobHandler autoAck;

  private Long openShopId;
  private Long closedShopId;

  @BeforeEach
  void insertBusinesses() {
    openShopId = insertBusiness("open-shop", "{}", "{\"autoAckMinutes\": 3}");
    closedShopId = insertBusiness("closed-shop", ALWAYS_CLOSED, "{\"autoAckMinutes\": 3}");
  }

  // --- creating a handoff ---

  @Test
  void handoffMovesConversationToWaitingWithSystemMessageAndOpenStatus() throws Exception {
    Conversation conversation = start(openShopId);

    long handoffId =
        createHandoff("open-shop", conversation.getId())
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andReturn()
            .getResponse()
            .getContentAsString()
            .transform(this::readId);

    assertThat(reload(conversation, openShopId).getState()).isEqualTo(ConversationState.WAITING);
    Message system = history(conversation, openShopId).getLast();
    assertThat(system.getSender()).isEqualTo(MessageSender.SYSTEM);
    assertThat(system.isInternal()).isTrue();
    assertThat(system.getMetadata())
        .containsEntry("event", "HANDOFF_CREATED")
        .containsEntry("reason", "COMPLAINT")
        .containsEntry("handoffId", (int) handoffId);
    assertThat(handoffs.findById(handoffId).orElseThrow().getStatus())
        .isEqualTo(HandoffStatus.OPEN);
  }

  @Test
  void handoffQueuesOneAutoAckAfterTheBusinessAutoAckMinutes() throws Exception {
    Long slowShopId = insertBusiness("slow-shop", "{}", "{\"autoAckMinutes\": 10}");
    Conversation conversation = start(slowShopId);

    Instant before = Instant.now();
    createHandoff("slow-shop", conversation.getId()).andExpect(status().isCreated());
    Instant after = Instant.now();

    List<Job> autoAcks = autoAckJobs(conversation);
    assertThat(autoAcks).hasSize(1);
    assertThat(autoAcks.getFirst().getRunAt())
        .isBetween(before.plus(Duration.ofMinutes(10)), after.plus(Duration.ofMinutes(10)));
  }

  @Test
  void handoffWhenClosedQueuesTheAutoAckForNow() throws Exception {
    Conversation conversation = start(closedShopId);

    Instant before = Instant.now();
    createHandoff("closed-shop", conversation.getId()).andExpect(status().isCreated());

    List<Job> autoAcks = autoAckJobs(conversation);
    assertThat(autoAcks).hasSize(1);
    assertThat(autoAcks.getFirst().getRunAt()).isBetween(before, Instant.now());
  }

  // --- unknown conversations (T42) ---
  // Every chat now starts its conversation in business-service (public API), so a handoff for a
  // conversation that is not in core.conversations for the business is an error, not a v0.1.0
  // leftover.

  @Test
  void handoffForUnknownConversationReturns404() throws Exception {
    UUID unknownConversation = UUID.randomUUID();

    createHandoff("open-shop", unknownConversation)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Conversation not found"));

    assertThat(handoffs.findByBusinessIdOrderByCreatedAtAsc(openShopId)).isEmpty();
    assertThat(jobs.findAll())
        .noneMatch(
            job -> unknownConversation.toString().equals(job.getPayload().get("conversationId")));
  }

  @Test
  void handoffWithConversationOfAnotherBusinessReturns404AndNeverTouchesIt() throws Exception {
    Conversation conversation = start(openShopId);

    // Seen from closed-shop the id is unknown: same 404 as an id that does not exist.
    createHandoff("closed-shop", conversation.getId())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Conversation not found"));

    assertThat(reload(conversation, openShopId).getState()).isEqualTo(ConversationState.AI);
    assertThat(history(conversation, openShopId)).isEmpty();
    assertThat(handoffs.findByBusinessIdOrderByCreatedAtAsc(openShopId)).isEmpty();
    assertThat(handoffs.findByBusinessIdOrderByCreatedAtAsc(closedShopId)).isEmpty();
    assertThat(autoAckJobs(conversation)).isEmpty();
  }

  // The rollback of a rejected handoff (nothing stored) is tested in HandoffRollbackTest: here the
  // test transaction would keep the rows written before the error.

  @Test
  void handoffForConversationNotInAiReturns409() throws Exception {
    Conversation conversation = start(openShopId);
    createHandoff("open-shop", conversation.getId()).andExpect(status().isCreated());

    createHandoff("open-shop", conversation.getId())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Conversation state changed"))
        .andExpect(jsonPath("$.state").value("WAITING"));
  }

  // --- the AI stays silent ---

  @Test
  void noAiReplyJobWhileWaitingOrHuman() throws Exception {
    Conversation conversation = start(openShopId);
    createHandoff("open-shop", conversation.getId()).andExpect(status().isCreated());
    UUID id = conversation.getId();

    conversationService.addCustomerMessage(id, openShopId, "Anyone there?");
    assertThat(conversationService.isAiAnswering(id, openShopId)).isFalse();
    conversationService.takeOver(id, openShopId, STAFF_ID);
    conversationService.addCustomerMessage(id, openShopId, "Thanks!");
    assertThat(conversationService.isAiAnswering(id, openShopId)).isFalse();

    assertThat(jobsFor(conversation, JobType.AI_REPLY)).isEmpty();
  }

  // --- the handoff status follows the conversation ---

  @Test
  void takeOverMovesTheHandoffToInProgressAndResolveToResolved() throws Exception {
    Conversation conversation = start(openShopId);
    long handoffId = handoffFor("open-shop", conversation);

    conversationService.takeOver(conversation.getId(), openShopId, STAFF_ID);
    assertThat(handoffs.findById(handoffId).orElseThrow().getStatus())
        .isEqualTo(HandoffStatus.IN_PROGRESS);

    conversationService.resolve(conversation.getId(), openShopId, STAFF_ID);
    assertThat(handoffs.findById(handoffId).orElseThrow().getStatus())
        .isEqualTo(HandoffStatus.RESOLVED);
  }

  @Test
  void releaseBackToAiResolvesTheHandoff() throws Exception {
    Conversation conversation = start(openShopId);
    long handoffId = handoffFor("open-shop", conversation);

    conversationService.release(conversation.getId(), openShopId, STAFF_ID);

    assertThat(handoffs.findById(handoffId).orElseThrow().getStatus())
        .isEqualTo(HandoffStatus.RESOLVED);
  }

  // --- auto-ack ---

  @Test
  void autoAckIsSentExactlyOnce() throws Exception {
    Conversation conversation = start(openShopId);
    handoffFor("open-shop", conversation);
    Job job = autoAckJobs(conversation).getFirst();

    Optional<Message> first = autoAck.handle(job);
    Optional<Message> second = autoAck.handle(job);

    assertThat(first).isPresent();
    assertThat(second).isEmpty();
    List<Message> acks = autoAckMessages(conversation, openShopId);
    assertThat(acks).hasSize(1);
    Message ack = acks.getFirst();
    assertThat(ack.getSender()).isEqualTo(MessageSender.SYSTEM);
    assertThat(ack.isInternal()).isFalse();
    assertThat(ack.getMetadata()).containsEntry("businessOpen", true).doesNotContainKey("opensAt");
    assertThat(reload(conversation, openShopId).getState()).isEqualTo(ConversationState.WAITING);
  }

  @Test
  void autoAckWhenClosedSaysTheBusinessIsClosed() throws Exception {
    Conversation conversation = start(closedShopId);
    handoffFor("closed-shop", conversation);

    autoAck.handle(autoAckJobs(conversation).getFirst());

    assertThat(autoAckMessages(conversation, closedShopId))
        .singleElement()
        .satisfies(ack -> assertThat(ack.getMetadata()).containsEntry("businessOpen", false));
  }

  @Test
  void noAutoAckOnceStaffTookOver() throws Exception {
    Conversation conversation = start(openShopId);
    handoffFor("open-shop", conversation);
    conversationService.takeOver(conversation.getId(), openShopId, STAFF_ID);

    assertThat(autoAck.handle(autoAckJobs(conversation).getFirst())).isEmpty();

    assertThat(autoAckMessages(conversation, openShopId)).isEmpty();
  }

  @Test
  void autoAckJobWithAnotherBusinessIdSendsNothing() throws Exception {
    Conversation conversation = start(openShopId);
    long handoffId = handoffFor("open-shop", conversation);
    Job forged = Job.autoAck(closedShopId, conversation.getId(), handoffId, Instant.now());

    assertThat(autoAck.handle(forged)).isEmpty();

    assertThat(autoAckMessages(conversation, openShopId)).isEmpty();
    assertThat(
            messages.findByConversationIdAndBusinessIdOrderByIdAsc(
                conversation.getId(), closedShopId))
        .isEmpty();
  }

  // --- helpers ---

  private Long insertBusiness(String slug, String openingHours, String handoffSettings) {
    jdbc.update(
        """
        INSERT INTO core.businesses
          (slug, name, category, default_language, timezone, opening_hours, handoff_settings,
           publishable_key)
        VALUES (?, ?, 'shop', 'pt', 'America/Sao_Paulo', CAST(? AS jsonb), CAST(? AS jsonb), ?)
        """,
        slug,
        slug,
        openingHours,
        handoffSettings,
        "pk_test_" + slug);
    return businesses.findBySlug(slug).orElseThrow().getId();
  }

  private Conversation start(Long businessId) {
    return conversationService.start(
        businessId, Channel.WEB, "pt", UUID.randomUUID().toString(), null);
  }

  private ResultActions createHandoff(String slug, UUID conversationId) throws Exception {
    String body =
        objectMapper.writeValueAsString(
            Map.of(
                "businessSlug",
                slug,
                "conversationId",
                conversationId,
                "reason",
                "COMPLAINT",
                "summary",
                "Customer is upset about a late order."));
    return mockMvc.perform(
        post("/api/v1/handoffs")
            .header("X-Api-Key", "test-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  /** Creates a handoff through the API and returns its id. */
  private long handoffFor(String slug, Conversation conversation) throws Exception {
    return createHandoff(slug, conversation.getId())
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getContentAsString()
        .transform(this::readId);
  }

  private long readId(String json) {
    try {
      return objectMapper.readTree(json).get("id").asLong();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Conversation reload(Conversation conversation, Long businessId) {
    return conversations.findByIdAndBusinessId(conversation.getId(), businessId).orElseThrow();
  }

  private List<Message> history(Conversation conversation, Long businessId) {
    return messages.findByConversationIdAndBusinessIdOrderByIdAsc(conversation.getId(), businessId);
  }

  private List<Message> autoAckMessages(Conversation conversation, Long businessId) {
    return history(conversation, businessId).stream()
        .filter(message -> "AUTO_ACK".equals(message.getMetadata().get("event")))
        .toList();
  }

  private List<Job> autoAckJobs(Conversation conversation) {
    return jobsFor(conversation, JobType.AUTO_ACK);
  }

  private List<Job> jobsFor(Conversation conversation, JobType type) {
    String id = conversation.getId().toString();
    return jobs.findAll().stream()
        .filter(job -> job.getType() == type)
        .filter(job -> id.equals(job.getPayload().get("conversationId")))
        .toList();
  }
}
