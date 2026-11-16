package dev.ocaina.tendwise.shared;

import java.util.List;

/**
 * A request body with invalid fields. Mapped to a 400 Problem Details response with an {@code
 * errors} list: one {@link FieldError} per problem, e.g. {"field": "widgetSettings.color",
 * "message": "must match \"#[0-9a-fA-F]{6}\""}.
 */
public class InvalidFieldsException extends RuntimeException {

  /** One problem. field is a JSON path such as "openingHours.mon[0].open". */
  public record FieldError(String field, String message) {}

  private final List<FieldError> errors;

  public InvalidFieldsException(List<FieldError> errors) {
    super("Some fields are not valid.");
    this.errors = List.copyOf(errors);
  }

  public List<FieldError> getErrors() {
    return errors;
  }
}
