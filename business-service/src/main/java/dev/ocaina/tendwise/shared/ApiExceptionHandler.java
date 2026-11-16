package dev.ocaina.tendwise.shared;

import dev.ocaina.tendwise.conversation.ConversationConflictException;
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

  /** Someone changed the conversation's state first: 409 with the state it is in now. */
  @ExceptionHandler(ConversationConflictException.class)
  ProblemDetail handleConversationConflict(ConversationConflictException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "The conversation is in state " + exception.getCurrentState() + ".");
    problem.setTitle("Conversation state changed");
    problem.setProperty("state", exception.getCurrentState());
    problem.setProperty("assigneeUserId", exception.getCurrentAssigneeUserId());
    return problem;
  }
}
