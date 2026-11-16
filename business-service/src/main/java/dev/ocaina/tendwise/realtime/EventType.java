package dev.ocaina.tendwise.realtime;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The live events (docs/architecture.md, "Real-time"). Each one says whether the widget stream gets
 * it; the panel stream gets them all.
 */
public enum EventType {
  /** A message was stored. The widget gets it only when the message is not internal. */
  MESSAGE_CREATED("message.created", true),
  CONVERSATION_STATE_CHANGED("conversation.state_changed", true),
  CONVERSATION_ASSIGNED("conversation.assigned", false),
  /** Sent from T44 (POST …/read). */
  CONVERSATION_READ("conversation.read", false),
  HANDOFF_CREATED("handoff.created", false),
  /** Sent from T44 (POST …/typing). */
  STAFF_TYPING("staff.typing", true),
  /** An AI_REPLY job started. Never stored. */
  AI_TYPING("ai.typing", true);

  private final String wireName;
  private final boolean forWidget;

  EventType(String wireName, boolean forWidget) {
    this.wireName = wireName;
    this.forWidget = forWidget;
  }

  /** The name in the NOTIFY payload and the SSE "event:" line, e.g. "message.created". */
  @JsonValue
  public String wireName() {
    return wireName;
  }

  /** True when the widget (the customer) may get this type of event. */
  public boolean forWidget() {
    return forWidget;
  }
}
