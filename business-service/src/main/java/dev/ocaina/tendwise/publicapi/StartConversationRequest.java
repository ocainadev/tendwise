package dev.ocaina.tendwise.publicapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /public/v1/conversations.
 *
 * @param publishableKey the business's publishable key, from the page that embeds the widget
 * @param language en, es or pt; optional (the business's default language is used for the greeting)
 */
public record StartConversationRequest(
    @NotBlank @Size(max = 64) String publishableKey,
    @Pattern(regexp = "en|es|pt") String language) {}
