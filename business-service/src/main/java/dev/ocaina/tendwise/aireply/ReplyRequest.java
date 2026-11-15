package dev.ocaina.tendwise.aireply;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/** Body of agent-service POST /internal/v1/replies (T37). */
public record ReplyRequest(
    @JsonProperty("conversation_id") UUID conversationId,
    @JsonProperty("business_slug") String businessSlug,
    String message,
    @JsonProperty("staff_messages") List<StaffMessage> staffMessages) {

  /** What a person told the customer since the last AI turn. author: the staff member's name. */
  public record StaffMessage(String author, String body) {}
}
