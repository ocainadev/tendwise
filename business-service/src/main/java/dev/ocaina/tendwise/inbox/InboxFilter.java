package dev.ocaina.tendwise.inbox;

import dev.ocaina.tendwise.conversation.ConversationState;

/**
 * The list's filters. A null field means "no filter".
 *
 * @param assigneeUserId "me" is already turned into the user's id
 * @param query text searched in the messages (trimmed, never blank)
 * @param cursor where the page starts; null for the first page
 */
record InboxFilter(
    ConversationState state, Long assigneeUserId, String query, InboxCursor cursor) {}
