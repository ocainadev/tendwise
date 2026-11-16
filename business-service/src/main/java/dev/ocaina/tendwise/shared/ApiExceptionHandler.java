package dev.ocaina.tendwise.shared;

import dev.ocaina.tendwise.conversation.ConversationConflictException;
import dev.ocaina.tendwise.conversation.ConversationState;
import dev.ocaina.tendwise.conversation.NotAssigneeException;
import dev.ocaina.tendwise.user.User;
import dev.ocaina.tendwise.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns domain exceptions into RFC 7807 Problem Details. Framework errors (bad parameters, wrong
 * method...) are already handled by spring.mvc.problemdetails.enabled.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private final UserRepository users;

  public ApiExceptionHandler(UserRepository users) {
    this.users = users;
  }

  @ExceptionHandler(NotFoundException.class)
  ProblemDetail handleNotFound(NotFoundException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    problem.setTitle(exception.getTitle());
    return problem;
  }

  @ExceptionHandler(BadRequestException.class)
  ProblemDetail handleBadRequest(BadRequestException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    problem.setTitle(exception.getTitle());
    return problem;
  }

  /** 400 with one entry per invalid field in "errors". */
  @ExceptionHandler(InvalidFieldsException.class)
  ProblemDetail handleInvalidFields(InvalidFieldsException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    problem.setTitle("Invalid request");
    problem.setProperty("errors", exception.getErrors());
    return problem;
  }

  @ExceptionHandler(ConflictException.class)
  ProblemDetail handleConflict(ConflictException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    problem.setTitle(exception.getTitle());
    return problem;
  }

  /**
   * Someone changed the conversation's state first: 409 with the state it is in now and who has it
   * (assigneeName is the display name, so the panel can say "Maria already took this
   * conversation").
   */
  @ExceptionHandler(ConversationConflictException.class)
  ProblemDetail handleConversationConflict(ConversationConflictException exception) {
    String assigneeName = nameOf(exception.getCurrentAssigneeUserId());
    String detail =
        exception.getCurrentState() == ConversationState.HUMAN && assigneeName != null
            ? assigneeName + " already took this conversation."
            : "The conversation is in state " + exception.getCurrentState() + ".";
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
    problem.setTitle("Conversation state changed");
    problem.setProperty("state", exception.getCurrentState());
    problem.setProperty("assigneeUserId", exception.getCurrentAssigneeUserId());
    problem.setProperty("assigneeName", assigneeName);
    return problem;
  }

  /** A staff member who is not the assignee tried to write to the customer (T44): 403. */
  @ExceptionHandler(NotAssigneeException.class)
  ProblemDetail handleNotAssignee(NotAssigneeException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
    problem.setTitle("Not the assignee");
    problem.setProperty("assigneeUserId", exception.getAssigneeUserId());
    problem.setProperty("assigneeName", nameOf(exception.getAssigneeUserId()));
    return problem;
  }

  private String nameOf(Long userId) {
    return userId == null ? null : users.findById(userId).map(User::getName).orElse(null);
  }
}
