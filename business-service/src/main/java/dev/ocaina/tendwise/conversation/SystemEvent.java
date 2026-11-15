package dev.ocaina.tendwise.conversation;

/**
 * What a SYSTEM message records (stored in metadata.event). The text is a short English
 * description; the panel and the widget can show their own translation based on the event.
 *
 * <p>internal = true means staff only. The customer sees only the events that tell them who is
 * answering (TAKEN_OVER, RELEASED) and the auto-ack. Later tasks add ASSIGNED and AI_FAILED.
 */
public enum SystemEvent {
  HANDOFF_CREATED("Handed off to the team.", true),
  TAKEN_OVER("A team member joined the conversation.", false),
  RELEASED("Back to the assistant.", false),
  RESOLVED("Conversation resolved.", true),
  REOPENED("The customer wrote again; conversation reopened.", true),
  /** metadata.businessOpen says which case; opensAt (ISO date-time) is set when closed. */
  AUTO_ACK("The team has been notified and will reply here as soon as possible.", false);

  private final String text;
  private final boolean internal;

  SystemEvent(String text, boolean internal) {
    this.text = text;
    this.internal = internal;
  }

  public String text() {
    return text;
  }

  public boolean internal() {
    return internal;
  }
}
