package dev.ocaina.tendwise.job;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * The queue is platform-wide (no business_id column: the business is inside payload). Only {@link
 * JobQueue} uses the worker queries below.
 */
public interface JobRepository extends JpaRepository<Job, Long> {

  /**
   * The next due PENDING job, locked until the transaction ends. SKIP LOCKED makes a second worker
   * (another thread or instance) skip the row instead of waiting for it, so two workers never take
   * the same job.
   *
   * <p>AI_REPLY jobs of one conversation run one at a time and in order: one is not taken while the
   * same conversation has an AI_REPLY that is RUNNING, or an older one still PENDING.
   */
  @Query(
      value =
          """
          SELECT * FROM core.jobs j
          WHERE j.status = 'PENDING' AND j.run_at <= :now
            AND (j.type <> 'AI_REPLY' OR NOT EXISTS (
                  SELECT 1 FROM core.jobs other
                  WHERE other.type = 'AI_REPLY'
                    AND other.payload ->> 'conversationId' = j.payload ->> 'conversationId'
                    AND (other.status = 'RUNNING'
                         OR (other.status = 'PENDING' AND other.id < j.id))))
          ORDER BY j.run_at, j.id
          LIMIT 1
          FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  Optional<Job> findNextDueForUpdate(Instant now);

  /**
   * Puts back jobs left RUNNING since before lockedBefore (the instance that took them died), so
   * another worker runs them. Their attempt still counts.
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE core.jobs SET status = 'PENDING', locked_at = NULL
          WHERE status = 'RUNNING' AND locked_at < :lockedBefore
          """,
      nativeQuery = true)
  int releaseStale(Instant lockedBefore);
}
