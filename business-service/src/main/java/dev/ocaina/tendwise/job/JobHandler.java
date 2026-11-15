package dev.ocaina.tendwise.job;

/**
 * Runs the jobs of one {@link JobType}. Every handler is a plain Spring bean; the {@link JobWorker}
 * finds it by {@link #type()}, so a new job type only needs a new handler bean (and the migration
 * that allows the type in core.jobs).
 *
 * <p>A handler may run more than once for the same job (a retry, or a crash after the work but
 * before the job was marked DONE), so it must be safe to repeat.
 */
public interface JobHandler {

  /** The job type this handler runs. Two handlers for one type stop the application at startup. */
  JobType type();

  /**
   * Does the work. Returning normally marks the job DONE (also when there was nothing to do). An
   * exception counts as a failed attempt: the job is retried later, up to the configured attempts.
   */
  void run(Job job) throws Exception;

  /**
   * Called once when the last attempt failed, before the job is marked FAILED. Default: nothing.
   */
  default void onFinalFailure(Job job) {}
}
