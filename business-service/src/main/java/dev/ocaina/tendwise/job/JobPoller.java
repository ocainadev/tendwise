package dev.ocaina.tendwise.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Wakes the {@link JobWorker} every tendwise.jobs.poll-interval (1 second). Turned off with
 * tendwise.jobs.worker-enabled=false (JOB_WORKER_ENABLED); the tests turn it off and call the
 * worker themselves.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "tendwise.jobs.worker-enabled", havingValue = "true")
public class JobPoller {

  private final JobWorker worker;

  public JobPoller(JobWorker worker) {
    this.worker = worker;
  }

  @Scheduled(
      initialDelayString = "${tendwise.jobs.poll-interval}",
      fixedDelayString = "${tendwise.jobs.poll-interval}")
  void poll() {
    worker.pollOnce();
  }
}
