package dev.ocaina.tendwise.handoff;

import jakarta.validation.constraints.NotNull;

/** Body of PATCH /handoffs/{id}. Only the status can change. */
public record UpdateHandoffStatusRequest(@NotNull HandoffStatus status) {}
