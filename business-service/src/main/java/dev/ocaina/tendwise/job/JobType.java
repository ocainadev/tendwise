package dev.ocaina.tendwise.job;

/** Later tasks add their own types (AUTO_ACK, SEND_EMAIL...) together with a migration. */
public enum JobType {
  /** Ask agent-service to answer the latest customer message (worker in T39). */
  AI_REPLY
}
