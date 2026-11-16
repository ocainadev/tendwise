package dev.ocaina.tendwise.settings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The part of businesses.handoff_settings the owner edits (T41, T53).
 *
 * @param autoAckMinutes minutes a handoff waits for staff before the customer gets one automatic
 *     message
 */
public record HandoffSettings(@NotNull @Min(1) @Max(60) Integer autoAckMinutes) {}
