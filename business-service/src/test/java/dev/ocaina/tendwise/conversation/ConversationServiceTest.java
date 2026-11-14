package dev.ocaina.tendwise.conversation;

import static dev.ocaina.tendwise.conversation.ConversationState.AI;
import static dev.ocaina.tendwise.conversation.ConversationState.HUMAN;
import static dev.ocaina.tendwise.conversation.ConversationState.RESOLVED;
import static dev.ocaina.tendwise.conversation.ConversationState.WAITING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ocaina.tendwise.TestcontainersConfiguration;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.handoff.HandoffReason;
import dev.ocaina.tendwise.job.Job;
import dev.ocaina.tendwise.job.JobRepository;
import dev.ocaina.tendwise.job.JobStatus;
import dev.ocaina.tendwise.job.JobType;
import dev.ocaina.tendwise.shared.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The state machine against a real PostgreSQL. Not {@code @Transactional}: the concurrency test
 * needs real commits. Every test starts its own conversation, so tests never share rows.
 */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@Import(TestcontainersConfiguration.class)
class ConversationServiceTest {

  private static final long STAFF_ID = 7L;

  @Autowired private ConversationService service;
  @Autowired private ConversationRepository conversations;
  @Autowired private MessageRepository messages;
  @Autowired private JobRepository jobs;
  @Autowired private BusinessRepository businesses;
  @Autowired private TransactionTemplate transaction;

  private Long businessId;
  private Long otherBusinessId;

  @BeforeEach
  void findSeededBusinesses() {
    businessId = businesses.findBySlug("joes-pizza").orElseThrow().getId();
    otherBusinessId = businesses.findBySlug("denim-corner").orElseThrow().getId();
  }

  // --- start and messages ---

  @Test
  void startCreatesConversationInAi() {
    Conversation conversation = start();

    assertThat(conversation.getState()).isEqualTo(AI);
    assertThat(conversation.getAssigneeUserId()).isNull();
    assertThat(conversation.getVersion()).isZero();
    assertThat(history(conversation)).isEmpty();
  }

  @Test
  void customerMessageInAiQueuesAiReplyJob() {
    Conversation conversation = start();

    Message message = service.addCustomerMessage(conversation.getId(), businessId, "Hi");

    assertThat(message.getSender()).isEqualTo(MessageSender.CUSTOMER);
    assertThat(jobsFor(conversation))
        .singleElement()
        .satisfies(job -> {
          assertThat(job.getType()).isEqualTo(JobType.AI_REPLY);
          assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
          assertThat(job.getPayload())
              .containsEntry("businessId", businessId.intValue())
              .containsEntry("messageId", message.getId().intValue());
        });
    Conversation reloaded = reload(conversation);
    assertThat(reloaded.getUnreadByStaff()).isEqualTo(1);
    assertThat(reloaded.getLastMessageAt()).isEqualTo(message.getCreatedAt());
  }

  @Test
  void customerMessageInWaitingOrHumanQueuesNoJob() {
    Conversation waiting = inState(WAITING);
    Conversation human = inState(HUMAN);

    service.addCustomerMessage(waiting.getId(), businessId, "Hello?");
    service.addCustomerMessage(human.getId(), businessId, "Hello?");

    assertThat(jobsFor(waiting)).isEmpty();
    assertThat(jobsFor(human)).isEmpty();
    assertThat(reload(waiting).getUnreadByStaff()).isEqualTo(1);
  }

  @Test
  void customerMessageInResolvedReopensInAi() {
    Conversation conversation = inState(RESOLVED);

    Message message = service.addCustomerMessage(conversation.getId(), businessId, "One more");

    Conversation reloaded = reload(conversation);
    assertThat(reloaded.getState()).isEqualTo(AI);
    assertThat(reloaded.getAssigneeUserId()).isNull();
    assertThat(reloaded.getResolvedAt()).isNull();
    List<Message> history = history(conversation);
    Message reopened = history.get(history.size() - 2);
    assertThat(reopened.getMetadata()).containsEntry("event", "REOPENED");
    assertThat(history.getLast().getId()).isEqualTo(message.getId());
    assertThat(jobsFor(conversation)).hasSize(1);
  }

  @Test
  void jobAndCustomerMessageAreOneTransaction() {
    Conversation conversation = start();

    // The caller's transaction rolls back after the call: the message and the job go together.
    transaction.executeWithoutResult(status -> {
      service.addCustomerMessage(conversation.getId(), businessId, "Hi");
      assertThat(jobsFor(conversation)).hasSize(1);
      status.setRollbackOnly();
    });

    assertThat(history(conversation)).isEmpty();
    assertThat(jobsFor(conversation)).isEmpty();
  }

  @Test
  void aiMessageIsStoredInAi() {
    Conversation conversation = start();

    var stored =
        service.addAiMessage(conversation.getId(), businessId, "Hello!", Map.of("intent", "faq"));

    assertThat(stored).isPresent();
    assertThat(stored.get().getSender()).isEqualTo(MessageSender.AI);
    assertThat(history(conversation).getFirst().getMetadata()).containsEntry("intent", "faq");
  }

  @Test
  void aiMessageIsDroppedWhenStaffHaveTheConversation() {
    Conversation conversation = inState(HUMAN);
    int before = history(conversation).size();

    assertThat(service.addAiMessage(conversation.getId(), businessId, "Hello!", Map.of()))
        .isEmpty();
    assertThat(history(conversation)).hasSize(before);
  }

  // --- valid transitions ---

  @Test
  void handOffMovesAiToWaiting() {
    Conversation conversation = start();

    Conversation result =
        service.handOff(conversation.getId(), businessId, HandoffReason.HUMAN_REQUEST);

    assertThat(result.getState()).isEqualTo(WAITING);
    assertThat(result.getVersion()).isEqualTo(1);
    Message system = history(conversation).getLast();
    assertThat(system.getSender()).isEqualTo(MessageSender.SYSTEM);
    assertThat(system.isInternal()).isTrue();
    assertThat(system.getMetadata())
        .containsEntry("event", "HANDOFF_CREATED")
        .containsEntry("reason", "HUMAN_REQUEST");
  }

  @Test
  void takeOverMovesWaitingToHuman() {
    Conversation conversation = inState(WAITING);

    Conversation result = service.takeOver(conversation.getId(), businessId, STAFF_ID);

    assertThat(result.getState()).isEqualTo(HUMAN);
    assertThat(result.getAssigneeUserId()).isEqualTo(STAFF_ID);
    Message system = history(conversation).getLast();
    assertThat(system.isInternal()).isFalse();
    assertThat(system.getMetadata()).containsEntry("event", "TAKEN_OVER");
  }

  @Test
  void releaseMovesWaitingOrHumanBackToAi() {
    for (ConversationState from : List.of(WAITING, HUMAN)) {
      Conversation conversation = inState(from);

      Conversation result = service.release(conversation.getId(), businessId, STAFF_ID);

      assertThat(result.getState()).isEqualTo(AI);
      assertThat(result.getAssigneeUserId()).isNull();
      assertThat(history(conversation).getLast().getMetadata()).containsEntry("event", "RELEASED");
    }
  }

  @Test
  void resolveWorksFromAiWaitingAndHuman() {
    for (ConversationState from : List.of(AI, WAITING, HUMAN)) {
      Conversation conversation = inState(from);

      Conversation result = service.resolve(conversation.getId(), businessId, STAFF_ID);

      assertThat(result.getState()).isEqualTo(RESOLVED);
      assertThat(result.getResolvedAt()).isNotNull();
      assertThat(result.getAssigneeUserId())
          .isEqualTo(from == HUMAN ? STAFF_ID : null); // the assignee is kept
      assertThat(history(conversation).getLast().getMetadata()).containsEntry("event", "RESOLVED");
    }
  }

  // --- invalid transitions ---

  @Test
  void invalidTransitionsAreRejectedAndWriteNothing() {
    assertRejected(WAITING, id -> service.handOff(id, businessId, HandoffReason.COMPLAINT));
    assertRejected(HUMAN, id -> service.handOff(id, businessId, HandoffReason.COMPLAINT));
    assertRejected(RESOLVED, id -> service.handOff(id, businessId, HandoffReason.COMPLAINT));
    assertRejected(AI, id -> service.takeOver(id, businessId, STAFF_ID));
    assertRejected(HUMAN, id -> service.takeOver(id, businessId, 99L));
    assertRejected(RESOLVED, id -> service.takeOver(id, businessId, STAFF_ID));
    assertRejected(AI, id -> service.release(id, businessId, STAFF_ID));
    assertRejected(RESOLVED, id -> service.release(id, businessId, STAFF_ID));
    assertRejected(RESOLVED, id -> service.resolve(id, businessId, STAFF_ID));
  }

  @Test
  void twoConcurrentTakeoversExactlyOneWins() throws Exception {
    Conversation conversation = inState(WAITING);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Future<Conversation>> results = new ArrayList<>();
    for (long userId : List.of(1L, 2L)) {
      Callable<Conversation> takeOver =
          () -> {
            go.await();
            return service.takeOver(conversation.getId(), businessId, userId);
          };
      results.add(pool.submit(takeOver));
    }
    go.countDown();

    int wins = 0;
    Long winner = null;
    for (Future<Conversation> result : results) {
      try {
        winner = result.get().getAssigneeUserId();
        wins++;
      } catch (ExecutionException e) {
        assertThat(e.getCause()).isInstanceOf(ConversationConflictException.class);
        assertThat(((ConversationConflictException) e.getCause()).getCurrentState())
            .isEqualTo(HUMAN);
      }
    }
    pool.shutdown();

    assertThat(wins).isEqualTo(1);
    assertThat(reload(conversation).getAssigneeUserId()).isEqualTo(winner);
    assertThat(history(conversation))
        .filteredOn(m -> "TAKEN_OVER".equals(m.getMetadata().get("event")))
        .hasSize(1);
  }

  // --- tenant isolation ---

  @Test
  void anotherBusinessCanNeverReachTheConversation() {
    Conversation conversation = inState(WAITING);
    UUID id = conversation.getId();

    assertThatThrownBy(() -> service.addCustomerMessage(id, otherBusinessId, "Hi"))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.addAiMessage(id, otherBusinessId, "Hi", Map.of()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.takeOver(id, otherBusinessId, STAFF_ID))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.release(id, otherBusinessId, STAFF_ID))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.resolve(id, otherBusinessId, STAFF_ID))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.handOff(id, otherBusinessId, HandoffReason.COMPLAINT))
        .isInstanceOf(NotFoundException.class);

    assertThat(conversations.findByIdAndBusinessId(id, otherBusinessId)).isEmpty();
    assertThat(messages.findByConversationIdAndBusinessIdOrderByIdAsc(id, otherBusinessId))
        .isEmpty();
    Conversation unchanged = reload(conversation);
    assertThat(unchanged.getState()).isEqualTo(WAITING);
    assertThat(unchanged.getVersion()).isEqualTo(conversation.getVersion());
    assertThat(unchanged.getUnreadByStaff()).isZero();
  }

  // --- helpers ---

  private interface Transition {
    void apply(UUID conversationId);
  }

  private void assertRejected(ConversationState from, Transition transition) {
    Conversation conversation = inState(from);
    int messagesBefore = history(conversation).size();

    assertThatThrownBy(() -> transition.apply(conversation.getId()))
        .isInstanceOf(ConversationConflictException.class)
        .extracting(e -> ((ConversationConflictException) e).getCurrentState())
        .isEqualTo(from);

    Conversation unchanged = reload(conversation);
    assertThat(unchanged.getState()).isEqualTo(from);
    assertThat(unchanged.getVersion()).isEqualTo(conversation.getVersion());
    assertThat(history(conversation)).hasSize(messagesBefore);
  }

  private Conversation start() {
    return service.start(businessId, Channel.WEB, "en", UUID.randomUUID().toString(), null);
  }

  /** A new conversation moved to the given state through the service. */
  private Conversation inState(ConversationState state) {
    UUID id = start().getId();
    if (state == WAITING || state == HUMAN) {
      service.handOff(id, businessId, HandoffReason.HUMAN_REQUEST);
    }
    if (state == HUMAN) {
      service.takeOver(id, businessId, STAFF_ID);
    }
    if (state == RESOLVED) {
      service.resolve(id, businessId, STAFF_ID);
    }
    return conversations.findByIdAndBusinessId(id, businessId).orElseThrow();
  }

  private Conversation reload(Conversation conversation) {
    return conversations.findByIdAndBusinessId(conversation.getId(), businessId).orElseThrow();
  }

  private List<Message> history(Conversation conversation) {
    return messages.findByConversationIdAndBusinessIdOrderByIdAsc(conversation.getId(), businessId);
  }

  private List<Job> jobsFor(Conversation conversation) {
    String id = conversation.getId().toString();
    return jobs.findAll().stream()
        .filter(job -> id.equals(job.getPayload().get("conversationId")))
        .toList();
  }
}
