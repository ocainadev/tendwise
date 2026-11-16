package dev.ocaina.tendwise.inbox;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Where the next page of the inbox list starts: the last conversation of the previous page. The
 * list is ordered by lastMessageAt, newest first, and the id breaks ties, so the pair is unique and
 * no conversation is skipped or repeated between pages (unless it gets a new message meanwhile:
 * then it moves to the top, and the web refetches the list on events anyway).
 *
 * <p>Sent to the client as an opaque base64url string, e.g. of {@code
 * 2026-11-15T10:00:00.123456Z|6f1c...}.
 */
record InboxCursor(Instant lastMessageAt, UUID id) {

  private static final String SEPARATOR = "|";

  String encode() {
    String text = lastMessageAt + SEPARATOR + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  /** Throws IllegalArgumentException for anything that this class did not encode. */
  static InboxCursor decode(String cursor) {
    try {
      String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int separator = text.indexOf(SEPARATOR);
      if (separator < 0) {
        throw new IllegalArgumentException("Invalid cursor");
      }
      return new InboxCursor(
          Instant.parse(text.substring(0, separator)),
          UUID.fromString(text.substring(separator + 1)));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid cursor", e);
    }
  }
}
