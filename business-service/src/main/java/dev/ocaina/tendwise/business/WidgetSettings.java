package dev.ocaina.tendwise.business;

import java.util.List;

/**
 * The businesses.widget_settings column (T38). The owner edits it in the panel later (T53).
 *
 * @param allowedDomains origins allowed to call the public API, e.g. "https://shop.example.com"
 * @param color widget accent color, e.g. "#1d4ed8" (used by the widget, T56)
 * @param position where the widget button sits, e.g. "bottom-right" (used by the widget, T56)
 */
public record WidgetSettings(List<String> allowedDomains, String color, String position) {

  /** A missing list means no origin is allowed. */
  public WidgetSettings {
    allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
  }
}
