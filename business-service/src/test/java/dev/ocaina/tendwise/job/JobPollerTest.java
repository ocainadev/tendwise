package dev.ocaina.tendwise.job;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ocaina.tendwise.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * T39: with the worker enabled (the production default) the poller starts and its schedule is read.
 * The interval is long, so it never actually polls during the test.
 */
@SpringBootTest(
    properties = {
      "tendwise.api-key=test-key",
      "tendwise.jobs.worker-enabled=true",
      "tendwise.jobs.poll-interval=1h"
    })
@Import(TestcontainersConfiguration.class)
class JobPollerTest {

  @Autowired private ApplicationContext context;

  @Test
  void thePollerRunsWhenTheWorkerIsEnabled() {
    assertThat(context.getBeansOfType(JobPoller.class)).hasSize(1);
  }
}
