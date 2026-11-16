package dev.ocaina.tendwise.inbox;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST …/conversations/{id}/messages (T44). Same size limit as the customer's messages in
 * the public API.
 *
 * @param internal true = a note for colleagues, never sent to the customer
 */
record StaffMessageRequest(@NotBlank @Size(max = MAX_LENGTH) String body, boolean internal) {

  static final int MAX_LENGTH = 4000;
}
