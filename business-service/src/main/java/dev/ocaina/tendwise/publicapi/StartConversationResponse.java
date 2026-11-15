package dev.ocaina.tendwise.publicapi;

import java.util.UUID;

/**
 * Answer of POST /public/v1/conversations.
 *
 * @param token the conversation token, shown only here. The widget sends it back in the
 *     Authorization header; only its SHA-256 is stored.
 */
public record StartConversationResponse(UUID conversationId, String token, String greeting) {}
