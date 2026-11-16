package dev.ocaina.tendwise.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ocaina.tendwise.conversation.Conversation;
import dev.ocaina.tendwise.conversation.Message;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends live events to every business-service instance with PostgreSQL {@code NOTIFY}
 * (docs/architecture.md, "Real-time"; ADR 0004). {@link NotificationListener} receives them.
 *
 * <p><b>Only after the commit.</b> Call these methods inside the transaction that stores the
 * change. The NOTIFY runs on that transaction's connection, and PostgreSQL delivers it only when
 * the transaction commits; a rollback throws it away. So a listener never hears about data it
 * cannot read yet, and never about data that was not saved. (Sending the NOTIFY from a Spring
 * after-commit callback instead would need a second pool connection while the first is still held,
 * which can exhaust the pool under load, and loses the event if the instance stops in between.)
 *
 * <p>Called outside a transaction (ai.typing), the NOTIFY is sent right away.
 */
@Component
public class EventPublisher {

  /** The channel every instance LISTENs on. */
  public static final String CHANNEL = "events";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public EventPublisher(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /** A message was stored (customer, AI, staff or SYSTEM). */
  public void messageCreated(Message message) {
    publish(
        new RealtimeEvent(
            message.getBusinessId(),
            message.getConversationId(),
            EventType.MESSAGE_CREATED,
            message.getId(),
            message.isInternal(),
            null));
  }

  /** The conversation's state changed; the event carries the new state. */
  public void stateChanged(Conversation conversation) {
    publish(
        new RealtimeEvent(
            conversation.getBusinessId(),
            conversation.getId(),
            EventType.CONVERSATION_STATE_CHANGED,
            null,
            false,
            conversation.getState().name()));
  }

  /** The conversation's assignee changed (taken over or released). */
  public void assigned(Conversation conversation) {
    publish(
        new RealtimeEvent(
            conversation.getBusinessId(),
            conversation.getId(),
            EventType.CONVERSATION_ASSIGNED,
            null,
            false,
            null));
  }

  public void handoffCreated(long businessId, UUID conversationId, long handoffId) {
    publish(
        new RealtimeEvent(
            businessId, conversationId, EventType.HANDOFF_CREATED, handoffId, false, null));
  }

  /** An AI_REPLY job is asking agent-service. Sent at once, never stored. */
  public void aiTyping(long businessId, UUID conversationId) {
    publish(new RealtimeEvent(businessId, conversationId, EventType.AI_TYPING, null, false, null));
  }

  void publish(RealtimeEvent event) {
    String payload;
    try {
      payload = objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not write a live event", e);
    }
    // pg_notify(channel, payload) is NOTIFY with parameters. It returns void, so the row is
    // ignored.
    jdbc.query("SELECT pg_notify(?, ?)", row -> {}, CHANNEL, payload);
  }
}
