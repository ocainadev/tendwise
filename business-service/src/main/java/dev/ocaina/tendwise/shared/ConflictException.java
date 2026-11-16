package dev.ocaina.tendwise.shared;

/**
 * Thrown by services when a request clashes with the current state (for example removing the last
 * owner). Mapped to a 409 Problem Details response.
 */
public class ConflictException extends RuntimeException {

  private final String title;

  public ConflictException(String title, String detail) {
    super(detail);
    this.title = title;
  }

  public String getTitle() {
    return title;
  }
}
