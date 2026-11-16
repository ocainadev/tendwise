package dev.ocaina.tendwise.inbox;

/** A list filter that cannot be used (bad cursor, assignee or limit). Answered with 400. */
class InvalidInboxRequestException extends RuntimeException {

  InvalidInboxRequestException(String message) {
    super(message);
  }
}
