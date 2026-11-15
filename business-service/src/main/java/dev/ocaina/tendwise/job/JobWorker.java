package dev.ocaina.tendwise.job;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * Runs the jobs of core.jobs (docs/architecture.md, "Jobs"; ADR 0004).
 *
 * <p>For each job: take it ({@link JobQueue#claimNext()}, SELECT … FOR UPDATE SKIP LOCKED), run the
 * {@link JobHandler} of its type, then mark it DONE. When the handler throws, the job goes back to
 * PENDING with a later run_at (the delay doubles each attempt) and last_error = the error type.
 * After the last attempt the handler's {@link JobHandler#onFinalFailure} runs and the job is
 * FAILED.
 *
 * <p>{@link JobPoller} calls {@link #pollOnce()} every second; tests call {@link #runNext()}.
 */
@Component
public class JobWorker {

  private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

  private final JobQueue queue;
  private final Map<JobType, JobHandler> handlers = new EnumMap<>(JobType.class);
  private final int maxAttempts;
  private final Duration retryDelay;
  private final Duration staleAfter;

  /** Limits how many jobs run at the same time on this instance. */
  private final Semaphore freeSlots;

  /** One cheap virtual thread per running job: it mostly waits for agent-service. */
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public JobWorker(
      JobQueue queue,
      List<JobHandler> handlerBeans,
      @Value("${tendwise.jobs.max-attempts}") int maxAttempts,
      @Value("${tendwise.jobs.retry-delay}") Duration retryDelay,
      @Value("${tendwise.jobs.stale-after}") Duration staleAfter,
      @Value("${tendwise.jobs.max-concurrent}") int maxConcurrent) {
    this.queue = queue;
    for (JobHandler handler : handlerBeans) {
      if (handlers.put(handler.type(), handler) != null) {
        throw new IllegalStateException("Two job handlers for type " + handler.type());
      }
    }
    this.maxAttempts = maxAttempts;
    this.retryDelay = retryDelay;
    this.staleAfter = staleAfter;
    this.freeSlots = new Semaphore(maxConcurrent);
  }

  /**
   * One poll: puts back stale RUNNING jobs, then starts due jobs on virtual threads while a slot is
   * free. Returns at once; the jobs finish in the background.
   */
  public void pollOnce() {
    int released = queue.releaseStale(Instant.now().minus(staleAfter));
    if (released > 0) {
      log.warn("Put {} stale RUNNING job(s) back to PENDING", released);
    }
    while (freeSlots.tryAcquire()) {
      Optional<Job> job = claimOrReleaseSlot();
      if (job.isEmpty()) {
        return;
      }
      executor.execute(() -> {
        try {
          run(job.get());
        } finally {
          freeSlots.release();
        }
      });
    }
  }

  /**
   * Claims the next due job. The slot just taken is given back when there is none (or on error).
   */
  private Optional<Job> claimOrReleaseSlot() {
    try {
      Optional<Job> job = queue.claimNext();
      if (job.isEmpty()) {
        freeSlots.release();
      }
      return job;
    } catch (RuntimeException e) {
      freeSlots.release();
      throw e;
    }
  }

  /** Takes and runs one due job on the calling thread. False when no job was due. */
  public boolean runNext() {
    Optional<Job> job = queue.claimNext();
    job.ifPresent(this::run);
    return job.isPresent();
  }

  /** Runs a job already taken (RUNNING) and records the outcome. Never throws. */
  private void run(Job job) {
    JobHandler handler = handlers.get(job.getType());
    try {
      if (handler == null) {
        throw new IllegalStateException("No handler for job type " + job.getType());
      }
      handler.run(job);
      queue.markDone(job.getId());
    } catch (Exception e) {
      recordFailure(job, handler, errorType(e));
    }
  }

  private void recordFailure(Job job, JobHandler handler, String error) {
    if (job.getAttempts() < maxAttempts) {
      Instant runAt = Instant.now().plus(backoff(job.getAttempts()));
      log.info(
          "Job {} ({}) attempt {} of {} failed: {}; retrying at {}",
          job.getId(),
          job.getType(),
          job.getAttempts(),
          maxAttempts,
          error,
          runAt);
      queue.retryAt(job.getId(), runAt, error);
      return;
    }
    log.warn(
        "Job {} ({}) failed after {} attempts: {}",
        job.getId(),
        job.getType(),
        job.getAttempts(),
        error);
    // First the handler's fallback, then FAILED: if this instance dies in between, the job is
    // still RUNNING, comes back as stale and the (repeatable) fallback runs again.
    if (handler != null) {
      try {
        handler.onFinalFailure(job);
      } catch (Exception e) {
        log.error("Final-failure step of job {} failed: {}", job.getId(), errorType(e));
      }
    }
    queue.markFailed(job.getId(), error);
  }

  /** retryDelay after the first attempt, then twice as long after each one (5s, 10s, 20s...). */
  Duration backoff(int attempts) {
    return retryDelay.multipliedBy(1L << Math.min(attempts - 1, 10));
  }

  /**
   * What last_error and the logs record: the exception class, plus the HTTP status for an HTTP
   * error. Never the exception message, which may contain message text or a response body.
   */
  static String errorType(Exception e) {
    String type = e.getClass().getSimpleName();
    if (e instanceof RestClientResponseException http) {
      type = type + " " + http.getStatusCode().value();
    }
    return type;
  }

  /** Lets running jobs finish for a few seconds on shutdown; unfinished ones come back as stale. */
  @PreDestroy
  void stop() throws InterruptedException {
    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);
  }
}
