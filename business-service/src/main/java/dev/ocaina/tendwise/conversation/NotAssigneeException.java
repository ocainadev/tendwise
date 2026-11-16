package dev.ocaina.tendwise.conversation;

/**
 * Only the assignee may write to the customer or send staff.typing (T44). Thrown when the
 * conversation is in HUMAN but assigned to someone else; the API answers 403 with who has it.
 */
public class NotAssigneeException extends RuntimeException {

  private final Long assigneeUserId;

  public NotAssigneeException(Long assigneeUserId) {
    super("Only the assignee can reply to the customer.");
    this.assigneeUserId = assigneeUserId;
  }

  public Long getAssigneeUserId() {
    return assigneeUserId;
  }
}
