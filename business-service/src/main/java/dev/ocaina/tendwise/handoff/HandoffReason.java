package dev.ocaina.tendwise.handoff;

/** Why the agent passed the conversation to a human. */
public enum HandoffReason {
  COMPLAINT,
  HUMAN_REQUEST,
  NEGATIVE_SENTIMENT,
  LOW_CONFIDENCE
}
