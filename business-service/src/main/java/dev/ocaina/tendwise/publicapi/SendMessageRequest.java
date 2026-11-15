package dev.ocaina.tendwise.publicapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /public/v1/conversations/{id}/messages. Same size limit as /api/v1/chat. */
public record SendMessageRequest(@NotBlank @Size(max = SendMessageRequest.MAX_LENGTH) String body) {

  static final int MAX_LENGTH = 4000;
}
