package dev.ocaina.tendwise.job;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Status changes of core.jobs, each in its own short transaction. The job itself runs between
 * {@link #claimNext()} and one of the mark methods, outside any transaction, so a slow call (the
 * LLM) never keeps a database connection or a row lock.
 */
@Service
public class JobQueue {

  private final JobRepository jobs;

  public JobQueue(JobRepository jobs) {
    this.jobs = jobs;
  }

  /** Takes the next due job: RUNNING, one more attempt, locked_at = now. Empty when none is due. */
  @Transactional
  public Optional<Job> claimNext() {
    Instant now = Instant.now();
    Optional<Job> job = jobs.findNextDueForUpdate(now);
    job.ifPresent(found -> found.markRunning(now));
    return job;
  }

  @Transactional
  public void markDone(Long jobId) {
    jobs.findById(jobId).ifPresent(Job::markDone);
  }

  @Transactional
  public void retryAt(Long jobId, Instant runAt, String error) {
    jobs.findById(jobId).ifPresent(job -> job.retryAt(runAt, error));
  }

  @Transactional
  public void markFailed(Long jobId, String error) {
    jobs.findById(jobId).ifPresent(job -> job.markFailed(error));
  }

  /** Returns the number of jobs put back to PENDING. */
  @Transactional
  public int releaseStale(Instant lockedBefore) {
    return jobs.releaseStale(lockedBefore);
  }
}
