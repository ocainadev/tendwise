package dev.ocaina.tendwise.conversation;

import java.util.UUID;

/**
 * A state change lost the race (or was not allowed): the conversation was not in an expected state.
 * Carries the current state and assignee so the API (T44) can answer 409 Conflict with "someone
 * already took this conversation".
 */
public class ConversationConflictException extends RuntimeException {

  private final UUID conversationId;
  private final ConversationState currentState;
  private final Long currentAssigneeUserId;

  public ConversationConflictException(Conversation current) {
    super("Conversation is in state " + current.getState());
    this.conversationId = current.getId();
    this.currentState = current.getState();
    this.currentAssigneeUserId = current.getAssigneeUserId();
  }

  public UUID getConversationId() {
    return conversationId;
  }

  public ConversationState getCurrentState() {
    return currentState;
  }

  public Long getCurrentAssigneeUserId() {
    return currentAssigneeUserId;
  }
}
