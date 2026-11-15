package dev.ocaina.tendwise.job;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ocaina.tendwise.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T39: the generic part of the job worker against a real PostgreSQL. Not {@code @Transactional}:
 * every claim must commit, as in production, so each test starts from an empty queue instead.
 */
@SpringBootTest(properties = "tendwise.api-key=test-key")
@Import(TestcontainersConfiguration.class)
class JobWorkerTest {

  private static final long BUSINESS_ID = 1L;

  @Autowired private JobQueue queue;
  @Autowired private JobWorker worker;
  @Autowired private JobRepository jobs;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ApplicationContext context;

  @BeforeEach
  void emptyQueue() {
    jdbc.update("DELETE FROM core.jobs");
  }

  @Test
  void twoWorkersNeverTakeTheSameJob() throws Exception {
    Set<Long> inserted =
        jobs
            .saveAll(
                IntStream.range(0, 40)
                    .mapToObj(i -> Job.aiReply(BUSINESS_ID, UUID.randomUUID(), (long) i))
                    .toList())
            .stream()
            .map(Job::getId)
            .collect(Collectors.toSet());

    // Four "workers" claim at the same time until the queue is empty.
    int workers = 4;
    ConcurrentLinkedQueue<Long> claimed = new ConcurrentLinkedQueue<>();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    List<Future<?>> running = new ArrayList<>();
    for (int i = 0; i < workers; i++) {
      running.add(
          pool.submit(() -> {
            start.await();
            Optional<Job> job;
            while ((job = queue.claimNext()).isPresent()) {
              claimed.add(job.get().getId());
            }
            return null;
          }));
    }
    start.countDown();
    for (Future<?> future : running) {
      future.get();
    }
    pool.shutdown();

    assertThat(claimed).doesNotHaveDuplicates();
    assertThat(Set.copyOf(claimed)).isEqualTo(inserted);
    assertThat(jobs.findAll()).allMatch(job -> job.getStatus() == JobStatus.RUNNING);
  }

  @Test
  void claimingSetsRunningOneMoreAttemptAndLockedAt() {
    Long id = jobs.save(Job.aiReply(BUSINESS_ID, UUID.randomUUID(), 1L)).getId();

    Job claimed = queue.claimNext().orElseThrow();

    assertThat(claimed.getId()).isEqualTo(id);
    Job stored = jobs.findById(id).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(JobStatus.RUNNING);
    assertThat(stored.getAttempts()).isEqualTo(1);
    assertThat(stored.getLockedAt()).isNotNull();
    assertThat(queue.claimNext()).isEmpty();
  }

  @Test
  void jobsThatAreNotDueYetAreNotTaken() {
    jobs.save(
        Job.autoAck(BUSINESS_ID, UUID.randomUUID(), 1L, Instant.now().plus(Duration.ofMinutes(3))));

    assertThat(queue.claimNext()).isEmpty();
  }

  @Test
  void aiReplyJobsOfOneConversationRunOneAtATimeInOrder() {
    UUID conversationId = UUID.randomUUID();
    Long first = jobs.save(Job.aiReply(BUSINESS_ID, conversationId, 1L)).getId();
    Long second = jobs.save(Job.aiReply(BUSINESS_ID, conversationId, 2L)).getId();
    Long otherConversation = jobs.save(Job.aiReply(BUSINESS_ID, UUID.randomUUID(), 3L)).getId();

    assertThat(queue.claimNext().orElseThrow().getId()).isEqualTo(first);
    // The second waits for the first; another conversation does not.
    assertThat(queue.claimNext().orElseThrow().getId()).isEqualTo(otherConversation);
    assertThat(queue.claimNext()).isEmpty();

    queue.markDone(first);
    assertThat(queue.claimNext().orElseThrow().getId()).isEqualTo(second);
  }

  @Test
  void anOlderAiReplyWaitingForItsRetryKeepsTheNextOneBack() {
    UUID conversationId = UUID.randomUUID();
    Long first = jobs.save(Job.aiReply(BUSINESS_ID, conversationId, 1L)).getId();
    jobs.save(Job.aiReply(BUSINESS_ID, conversationId, 2L));

    queue.claimNext();
    queue.retryAt(first, Instant.now().plus(Duration.ofMinutes(1)), "SomeError");

    assertThat(queue.claimNext()).isEmpty();
  }

  @Test
  void aJobLeftRunningByADeadInstanceGoesBackToPending() {
    Long id = jobs.save(Job.aiReply(BUSINESS_ID, UUID.randomUUID(), 1L)).getId();
    queue.claimNext();
    jdbc.update("UPDATE core.jobs SET locked_at = now() - interval '1 hour' WHERE id = ?", id);

    worker.pollOnce();

    // pollOnce put it back and took it again: a second attempt. Unknown conversation: skipped.
    awaitStatus(id, JobStatus.DONE);
    assertThat(jobs.findById(id).orElseThrow().getAttempts()).isEqualTo(2);
  }

  @Test
  void autoAckJobsRunThroughTheirHandler() {
    // Unknown handoff: AutoAckJobHandler sends nothing, and the job is DONE (not "no handler").
    Long id = jobs.save(Job.autoAck(BUSINESS_ID, UUID.randomUUID(), 999L, Instant.now())).getId();

    assertThat(worker.runNext()).isTrue();

    Job job = jobs.findById(id).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
    assertThat(job.getLastError()).isNull();
    assertThat(worker.runNext()).isFalse();
  }

  @Test
  void testsRunWithoutTheBackgroundPoller() {
    // src/test/resources/config/application.yml turns it off; tests call the worker themselves.
    assertThat(context.getBeansOfType(JobPoller.class)).isEmpty();
  }

  @Test
  void backoffDoublesAfterEachAttempt() {
    assertThat(worker.backoff(1)).isEqualTo(Duration.ofSeconds(5));
    assertThat(worker.backoff(2)).isEqualTo(Duration.ofSeconds(10));
    assertThat(worker.backoff(3)).isEqualTo(Duration.ofSeconds(20));
  }

  private void awaitStatus(Long id, JobStatus status) {
    Instant deadline = Instant.now().plusSeconds(10);
    while (jobs.findById(id).orElseThrow().getStatus() != status) {
      assertThat(Instant.now()).as("job %d reaches %s", id, status).isBefore(deadline);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }
}
