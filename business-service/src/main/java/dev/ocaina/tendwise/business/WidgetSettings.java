package dev.ocaina.tendwise.business;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The businesses.widget_settings column (T38). The owner edits it in the panel (T53).
 *
 * @param allowedDomains origins allowed to call the public API, e.g. "https://shop.example.com"
 * @param color widget accent color, e.g. "#1d4ed8" (used by the widget, T56)
 * @param position where the widget button sits: "bottom-right" or "bottom-left" (T56)
 */
public record WidgetSettings(
    @Size(max = 20) List<String> allowedDomains,
    @Pattern(regexp = "#[0-9a-fA-F]{6}") String color,
    @Pattern(regexp = "bottom-right|bottom-left") String position) {

  /** A missing list means no origin is allowed. */
  public WidgetSettings {
    allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
  }
}
