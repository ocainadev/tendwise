package dev.ocaina.tendwise.inbox;

import jakarta.validation.constraints.NotNull;

/** Body of POST …/conversations/{id}/assign (T44): the member who gets the conversation. */
record AssignRequest(@NotNull Long userId) {}
