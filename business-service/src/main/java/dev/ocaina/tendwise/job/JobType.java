package dev.ocaina.tendwise.job;

/**
 * Later tasks add their own types (SEND_EMAIL...) together with a migration that allows them in
 * core.jobs, and a {@link JobHandler} bean that runs them.
 */
public enum JobType {
  /** Ask agent-service to answer a customer message (T39, AiReplyJobHandler). */
  AI_REPLY,
  /** Tell a waiting customer, once, that the team was notified (T41, AutoAckJobHandler). */
  AUTO_ACK
}
