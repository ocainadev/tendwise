package dev.ocaina.tendwise.shared;

/** Thrown by services when a resource does not exist. Mapped to a 404 Problem Details response. */
public class NotFoundException extends RuntimeException {

  private final String title;

  public NotFoundException(String title, String detail) {
    super(detail);
    this.title = title;
  }

  public String getTitle() {
    return title;
  }
}
