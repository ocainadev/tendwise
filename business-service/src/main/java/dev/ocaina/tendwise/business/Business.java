package dev.ocaina.tendwise.business;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A tenant: one small business using Tendwise. */
@Entity
@Table(name = "businesses")
public class Business {

  static final int DEFAULT_AUTO_ACK_MINUTES = 3;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String slug;

  private String name;

  private String category;

  /** Two-letter code: en, es or pt. */
  private String defaultLanguage;

  /** IANA time zone, e.g. America/Sao_Paulo. */
  private String timezone;

  /** Raw JSON, e.g. {"mon": [{"open": "11:00", "close": "23:00"}], "sun": []}. */
  @JdbcTypeCode(SqlTypes.JSON)
  private String openingHours;

  /** Starts a widget conversation. Public by design: it is in the page that embeds the widget. */
  private String publishableKey;

  @JdbcTypeCode(SqlTypes.JSON)
  private WidgetSettings widgetSettings;

  /** Raw JSON, e.g. {"autoAckMinutes": 3} (T41). Read through {@link #autoAckMinutes()}. */
  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> handoffSettings;

  /** Assistant name, tone, greeting, languages... (T53). Read through getAssistantSettings(). */
  @JdbcTypeCode(SqlTypes.JSON)
  private AssistantSettings assistantSettings;

  /** Set by the database default. */
  @Column(insertable = false, updatable = false)
  private Instant createdAt;

  /** Required by JPA. */
  protected Business() {}

  public Long getId() {
    return id;
  }

  public String getSlug() {
    return slug;
  }

  public String getName() {
    return name;
  }

  public String getCategory() {
    return category;
  }

  public String getDefaultLanguage() {
    return defaultLanguage;
  }

  public String getTimezone() {
    return timezone;
  }

  public String getOpeningHours() {
    return openingHours;
  }

  public String getPublishableKey() {
    return publishableKey;
  }

  public WidgetSettings getWidgetSettings() {
    return widgetSettings == null ? new WidgetSettings(null, null, null) : widgetSettings;
  }

  /** Minutes a handoff waits for staff before the automatic message. 3 when not set. */
  public int autoAckMinutes() {
    Object value = handoffSettings == null ? null : handoffSettings.get("autoAckMinutes");
    return value instanceof Number number ? number.intValue() : DEFAULT_AUTO_ACK_MINUTES;
  }

  /** The stored assistant settings; all languages when none were chosen yet. */
  public AssistantSettings getAssistantSettings() {
    AssistantSettings settings =
        assistantSettings == null
            ? new AssistantSettings(null, null, null, null, null, null)
            : assistantSettings;
    return settings.withDefaultLanguages();
  }

  /**
   * Replaces what the owner edits on the settings page (T53). The caller has validated every value.
   * Other keys of handoff_settings (e.g. waitingAlertMinutes, T48) are kept.
   */
  public void updateSettings(
      String name,
      String category,
      String defaultLanguage,
      String timezone,
      String openingHoursJson,
      AssistantSettings assistantSettings,
      int autoAckMinutes,
      WidgetSettings widgetSettings) {
    this.name = name;
    this.category = category;
    this.defaultLanguage = defaultLanguage;
    this.timezone = timezone;
    this.openingHours = openingHoursJson;
    this.assistantSettings = assistantSettings;
    Map<String, Object> handoff =
        handoffSettings == null ? new HashMap<>() : new HashMap<>(handoffSettings);
    handoff.put("autoAckMinutes", autoAckMinutes);
    this.handoffSettings = handoff;
    this.widgetSettings = widgetSettings;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
