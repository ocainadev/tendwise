package dev.ocaina.tendwise.aireply;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Answer of agent-service POST /internal/v1/replies (T37). intent is null when the guardrails
 * blocked the message; handoff is true when the agent created a handoff during this turn.
 */
public record ReplyResponse(
    String reply,
    String intent,
    String language,
    List<Source> sources,
    boolean handoff,
    @JsonProperty("order_code") String orderCode) {

  /** A knowledge-base section the reply was based on. */
  public record Source(
      String title, @JsonProperty("source_path") String sourcePath, String section) {}
}
