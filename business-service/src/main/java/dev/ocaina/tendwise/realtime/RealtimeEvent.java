package dev.ocaina.tendwise.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * One live event, as it travels in the NOTIFY payload between instances. Ids only, never message
 * text or personal data: the browser fetches the rest through the normal API.
 *
 * @param businessId which business's streams get it (every stream is for one business)
 * @param conversationId the conversation it is about
 * @param type what happened
 * @param id the message id (message.created) or handoff id (handoff.created), else null
 * @param internal true for an internal message: it never reaches the widget
 * @param state the conversation's new state (conversation.state_changed), else null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RealtimeEvent(
    long businessId,
    UUID conversationId,
    EventType type,
    Long id,
    boolean internal,
    String state) {}
