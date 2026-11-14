package dev.ocaina.tendwise.conversation;

/** Who answers the customer. See the state machine in docs/architecture.md. */
public enum ConversationState {
  /** The AI answers every customer message. */
  AI,
  /** Handed off: the AI is silent and staff have not taken over yet. */
  WAITING,
  /** A staff member (the assignee) answers. */
  HUMAN,
  /** Closed. A new customer message reopens it in AI. */
  RESOLVED
}
