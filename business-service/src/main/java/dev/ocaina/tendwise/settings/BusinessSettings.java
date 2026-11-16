package dev.ocaina.tendwise.settings;

import dev.ocaina.tendwise.business.AssistantSettings;
import dev.ocaina.tendwise.business.WidgetSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * Body of GET and PUT /api/v1/panel/{slug}/settings (T53). PUT replaces everything, so the web app
 * sends back what GET returned, with its changes. Checks that annotations cannot express (time
 * zone, opening hours, allowed domains, languages) are in {@link SettingsService}.
 *
 * @param openingHours e.g. {"mon": [], "tue": [{"open": "11:00", "close": "22:00"}]}; {} means the
 *     hours are unknown and the business counts as always open
 */
public record BusinessSettings(
    @NotBlank @Size(max = 120) String name,
    @NotBlank @Size(max = 64) String category,
    @NotNull @Pattern(regexp = "en|es|pt") String defaultLanguage,
    @NotBlank @Size(max = 64) String timezone,
    @NotNull Map<String, List<TimeRange>> openingHours,
    @NotNull @Valid AssistantSettings assistantSettings,
    @NotNull @Valid HandoffSettings handoffSettings,
    @NotNull @Valid WidgetSettings widgetSettings) {}
