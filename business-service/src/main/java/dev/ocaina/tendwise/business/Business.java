package dev.ocaina.tendwise.business;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
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

  public Instant getCreatedAt() {
    return createdAt;
  }
}
