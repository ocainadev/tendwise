package dev.ocaina.tendwise.business;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The businesses.assistant_settings column (T53). The owner edits it in the panel; the agent reads
 * it through GET /api/v1/businesses/{slug} (T54). Every text is optional: null keeps the agent's
 * default. The texts are written by the merchant, so they are length-capped (docs/panels-plan.md,
 * section 6).
 *
 * @param name the assistant's name, e.g. "Joe's helper"
 * @param tone how the assistant talks, e.g. "friendly and short"
 * @param greeting the first message of a conversation
 * @param extraInstructions more rules for the assistant (max 1,000 characters)
 * @param languages the languages the assistant answers in (en, es, pt)
 * @param outOfHoursMessage what a customer is told when nobody can answer now
 */
public record AssistantSettings(
    @Size(max = 60) String name,
    @Size(max = 200) String tone,
    @Size(max = 500) String greeting,
    @Size(max = 1000) String extraInstructions,
    @NotEmpty @Size(max = 3) List<@NotNull @Pattern(regexp = "en|es|pt") String> languages,
    @Size(max = 500) String outOfHoursMessage) {

  /** The languages a business answers in until the owner chooses. */
  public static final List<String> ALL_LANGUAGES = List.of("en", "es", "pt");

  /** The same settings, with all languages when none were chosen yet. */
  AssistantSettings withDefaultLanguages() {
    if (languages != null && !languages.isEmpty()) {
      return this;
    }
    return new AssistantSettings(
        name, tone, greeting, extraInstructions, ALL_LANGUAGES, outOfHoursMessage);
  }
}
