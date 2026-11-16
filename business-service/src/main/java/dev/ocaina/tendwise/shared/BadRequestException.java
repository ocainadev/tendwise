package dev.ocaina.tendwise.shared;

/**
 * Thrown by services when a request is well formed but cannot be done as asked. Mapped to a 400
 * Problem Details response.
 */
public class BadRequestException extends RuntimeException {

  private final String title;

  public BadRequestException(String title, String detail) {
    super(detail);
    this.title = title;
  }

  public String getTitle() {
    return title;
  }
}
