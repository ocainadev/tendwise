package dev.ocaina.tendwise.publicapi;

/**
 * The request comes from a website that is not in the business's allowed domains. Mapped to 403.
 */
class OriginNotAllowedException extends RuntimeException {

  OriginNotAllowedException() {
    super("This website is not allowed to use this business's chat.");
  }
}
