package dev.ocaina.tendwise.handoff;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Body of POST /handoffs, sent by agent-service. An unknown reason or a malformed UUID fails JSON
 * parsing, which Spring also turns into a 400.
 */
public record CreateHandoffRequest(
    @NotBlank String businessSlug,
    @NotNull UUID conversationId,
    @NotNull HandoffReason reason,
    // The column is TEXT; the limit keeps a runaway LLM summary out of the database.
    @NotBlank @Size(max = 2000) String summary) {}
