package dev.ocaina.tendwise.publicapi;

/** Too many requests from one IP or too many messages in one conversation. Mapped to 429. */
class RateLimitExceededException extends RuntimeException {

  RateLimitExceededException(String detail) {
    super(detail);
  }
}
