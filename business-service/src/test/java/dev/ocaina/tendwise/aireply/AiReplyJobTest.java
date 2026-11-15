package dev.ocaina.tendwise.aireply;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
import dev.ocaina.tendwise.handoff.HandoffReason;
import dev.ocaina.tendwise.job.Job;
import dev.ocaina.tendwise.job.JobRepository;
import dev.ocaina.tendwise.job.JobStatus;
import dev.ocaina.tendwise.job.JobWorker;
import dev.ocaina.tendwise.user.User;
import dev.ocaina.tendwise.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * T39: the AI_REPLY job end to end: a customer message queues the job, {@link JobWorker#runNext()}
 * runs it against a fake agent-service ({@link AgentServiceStub}) and a real PostgreSQL.
 *
 * <p>Not {@code @Transactional}: the worker commits each step, as in production. Each test uses a
 * new conversation and starts from an empty queue.
 */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@Import(TestcontainersConfiguration.class)
class AiReplyJobTest {

  private static final AgentServiceStub agentService = new AgentServiceStub();

  @DynamicPropertySource
  static void agentServiceUrl(DynamicPropertyRegistry registry) {
    registry.add("tendwise.agent-service.url", agentService::url);
  }

  @Autowired private JobWorker worker;
  @Autowired private ConversationService conversationService;
  @Autowired private ConversationRepository conversations;
  @Autowired private MessageRepository messages;
  @Autowired private JobRepository jobs;
  @Autowired private BusinessRepository businesses;
  @Autowired private UserRepository users;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  private Long businessId;
  private Conversation conversation;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM core.jobs");
    agentService.reset();
    businessId = businesses.findBySlug("joes-pizza").orElseThrow().getId();
    conversation = conversationService.start(businessId, Channel.WEB, "en", "visitor-1", null);
  }

  @Test
  void successStoresTheAiMessageWithMetadata() throws Exception {
    Message customer = customerSays("When do you open?");

    assertThat(worker.runNext()).isTrue();

    Message ai = history().getLast();
    assertThat(ai.getSender()).isEqualTo(MessageSender.AI);
    assertThat(ai.getBody()).isEqualTo("We open at 9.");
    assertThat(ai.isInternal()).isFalse();
    assertThat(ai.getMetadata())
        .containsEntry("intent", "faq")
        .containsEntry("language", "en")
        .containsEntry("orderCode", null)
        .containsEntry(
            "sources",
            List.of(
                Map.of(
                    "title", "Hours",
                    "source_path", "joes-pizza/hours.md",
                    "section", "Opening")));
    assertThat(onlyJob().getStatus()).isEqualTo(JobStatus.DONE);

    AgentServiceStub.Received call = agentService.received().getFirst();
    assertThat(call.apiKey()).isEqualTo("test-key");
    JsonNode body = objectMapper.readTree(call.body());
    assertThat(body.get("conversation_id").asText()).isEqualTo(conversation.getId().toString());
    assertThat(body.get("business_slug").asText()).isEqualTo("joes-pizza");
    assertThat(body.get("message").asText()).isEqualTo(customer.getBody());
    assertThat(body.get("staff_messages")).isEmpty();
  }

  @Test
  void aFailedAttemptIsRetriedLaterThenSucceeds() {
    agentService.answer(503, "{\"detail\": \"Assistant temporarily unavailable\"}");
    customerSays("When do you open?");

    worker.runNext();

    Job job = onlyJob();
    assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
    assertThat(job.getAttempts()).isEqualTo(1);
    // The error type and status only, never the response body.
    assertThat(job.getLastError()).isEqualTo("ServiceUnavailable 503");
    assertThat(job.getRunAt()).isAfter(Instant.now());
    assertThat(worker.runNext()).as("not due before the backoff").isFalse();

    makeDue(job);
    worker.runNext();

    assertThat(onlyJob().getStatus()).isEqualTo(JobStatus.DONE);
    assertThat(onlyJob().getAttempts()).isEqualTo(2);
    assertThat(history().getLast().getSender()).isEqualTo(MessageSender.AI);
    assertThat(reloadState()).isEqualTo(ConversationState.AI);
  }

  @Test
  void theLastFailedAttemptMovesTheConversationToWaiting() {
    for (int i = 0; i < 3; i++) {
      agentService.answer(503, "{\"detail\": \"Assistant temporarily unavailable\"}");
    }
    customerSays("When do you open?");

    for (int attempt = 1; attempt <= 3; attempt++) {
      makeDue(onlyJob());
      worker.runNext();
    }

    Job job = onlyJob();
    assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(job.getAttempts()).isEqualTo(3);
    assertThat(job.getLastError()).isEqualTo("ServiceUnavailable 503");
    assertThat(agentService.received()).hasSize(3);

    assertThat(reloadState()).isEqualTo(ConversationState.WAITING);
    Message system = history().getLast();
    assertThat(system.getSender()).isEqualTo(MessageSender.SYSTEM);
    assertThat(system.getMetadata()).containsEntry("event", "AI_FAILED");
    assertThat(system.isInternal()).as("the customer is told a person continues").isFalse();
    assertThat(history()).noneMatch(message -> message.getSender() == MessageSender.AI);
  }

  @Test
  void anEmptyReplyIsAFailedAttemptToo() {
    customerSays("Hi");
    agentService.answer(200, "{\"reply\": \"\", \"handoff\": false}");

    worker.runNext();

    assertThat(onlyJob().getStatus()).isEqualTo(JobStatus.PENDING);
    assertThat(onlyJob().getLastError()).isEqualTo("IllegalStateException");
  }

  @Test
  void theJobIsSkippedWhenTheConversationLeftAi() {
    customerSays("I want to talk to a person");
    conversationService.handOff(conversation.getId(), businessId, HandoffReason.HUMAN_REQUEST, 1L);

    worker.runNext();

    assertThat(onlyJob().getStatus()).isEqualTo(JobStatus.DONE);
    assertThat(agentService.received()).as("the AI is not even asked").isEmpty();
    assertThat(history()).noneMatch(message -> message.getSender() == MessageSender.AI);
  }

  @Test
  void staffMessagesSinceTheLastAiMessageAreSent() throws Exception {
    User maria = users.save(new User(UUID.randomUUID() + "@joes.test", "x", "Maria", null));
    staffSays(maria, "Old message, already sent before the AI answered", false);
    conversationService.addAiMessage(conversation.getId(), businessId, "Hello!", Map.of());
    staffSays(maria, "We have gluten-free crust.", false);
    staffSays(maria, "Internal note: customer is a regular", true);
    staffSays(maria, "Ask the assistant for prices.", false);
    customerSays("How much is it?");

    worker.runNext();

    JsonNode staff =
        objectMapper.readTree(agentService.received().getFirst().body()).get("staff_messages");
    assertThat(staff).hasSize(2);
    assertThat(staff.get(0).get("author").asText()).isEqualTo("Maria");
    assertThat(staff.get(0).get("body").asText()).isEqualTo("We have gluten-free crust.");
    assertThat(staff.get(1).get("body").asText()).isEqualTo("Ask the assistant for prices.");
  }

  @Test
  void aHandoffDuringTheCallKeepsWaitingAndStoresTheReply() {
    customerSays("I want to complain");
    agentService.answerAfter(
        () ->
            conversationService.handOff(
                conversation.getId(), businessId, HandoffReason.COMPLAINT, 1L),
        """
        {"reply": "A person from the team will continue here.", "intent": "complaint",
         "language": "en", "sources": [], "handoff": true, "order_code": null}
        """);

    worker.runNext();

    assertThat(reloadState()).isEqualTo(ConversationState.WAITING);
    Message ai = history().getLast();
    assertThat(ai.getSender()).isEqualTo(MessageSender.AI);
    assertThat(ai.getBody()).isEqualTo("A person from the team will continue here.");
    assertThat(onlyJob().getStatus()).isEqualTo(JobStatus.DONE);
  }

  @Test
  void theReplyIsDroppedWhenStaffTookOverDuringTheCall() {
    customerSays("Hello?");
    agentService.answerAfter(
        () -> {
          conversationService.handOff(
              conversation.getId(), businessId, HandoffReason.HUMAN_REQUEST, 1L);
          conversationService.takeOver(conversation.getId(), businessId, 7L);
        },
        AgentServiceStub.OK_REPLY);

    worker.runNext();

    assertThat(reloadState()).isEqualTo(ConversationState.HUMAN);
    assertThat(history()).noneMatch(message -> message.getSender() == MessageSender.AI);
    assertThat(onlyJob().getStatus()).isEqualTo(JobStatus.DONE);
  }

  // --- helpers ---

  private Message customerSays(String body) {
    return conversationService.addCustomerMessage(conversation.getId(), businessId, body);
  }

  /** HUMAN messages are written by T44's staff API; here they are inserted directly. */
  private void staffSays(User author, String body, boolean internal) {
    jdbc.update(
        """
        INSERT INTO core.messages
          (conversation_id, business_id, sender, author_user_id, body, internal, created_at)
        VALUES (?, ?, 'HUMAN', ?, ?, ?, now())
        """,
        conversation.getId(),
        businessId,
        author.getId(),
        body,
        internal);
  }

  private void makeDue(Job job) {
    jdbc.update("UPDATE core.jobs SET run_at = now() WHERE id = ?", job.getId());
  }

  private Job onlyJob() {
    List<Job> all = jobs.findAll();
    assertThat(all).hasSize(1);
    return all.getFirst();
  }

  private List<Message> history() {
    return messages.findByConversationIdAndBusinessIdOrderByIdAsc(conversation.getId(), businessId);
  }

  private ConversationState reloadState() {
    return conversations.findById(conversation.getId()).orElseThrow().getState();
  }
}
