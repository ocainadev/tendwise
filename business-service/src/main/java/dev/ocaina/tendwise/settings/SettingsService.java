package dev.ocaina.tendwise.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import dev.ocaina.tendwise.business.AssistantSettings;
import dev.ocaina.tendwise.business.Business;
import dev.ocaina.tendwise.business.BusinessRepository;
import dev.ocaina.tendwise.business.WidgetSettings;
import dev.ocaina.tendwise.shared.InvalidFieldsException;
import dev.ocaina.tendwise.shared.InvalidFieldsException.FieldError;
import dev.ocaina.tendwise.shared.NotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and replaces a business's settings (T53): profile and opening hours (columns of {@code
 * businesses}), and the assistant, handoff and widget settings (jsonb columns).
 *
 * <p>Validation, in order: unknown fields and wrong types (while reading the JSON), the annotations
 * on the records, then the checks below. All problems found are returned together.
 */
@Service
public class SettingsService {

  static final List<String> DAYS = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");
  static final int MAX_RANGES_PER_DAY = 4;
  private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

  private final BusinessRepository businesses;
  private final ObjectMapper objectMapper;
  private final Validator validator;

  /** Like the app's ObjectMapper, but a field the records do not have is an error. */
  private final ObjectReader strictReader;

  public SettingsService(
      BusinessRepository businesses, ObjectMapper objectMapper, Validator validator) {
    this.businesses = businesses;
    this.objectMapper = objectMapper;
    this.validator = validator;
    this.strictReader =
        objectMapper
            .readerFor(BusinessSettings.class)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
  }

  @Transactional(readOnly = true)
  public BusinessSettings get(long businessId) {
    return toSettings(findBusiness(businessId));
  }

  @Transactional
  public BusinessSettings update(long businessId, JsonNode body) {
    BusinessSettings settings = read(body);
    List<FieldError> errors = new ArrayList<>();
    for (ConstraintViolation<BusinessSettings> violation : validator.validate(settings)) {
      errors.add(new FieldError(fieldOf(violation), violation.getMessage()));
    }
    checkTimezone(settings.timezone(), errors);
    checkOpeningHours(settings.openingHours(), errors);
    checkLanguages(settings, errors);
    checkAllowedDomains(settings.widgetSettings(), errors);
    if (!errors.isEmpty()) {
      errors.sort(Comparator.comparing(FieldError::field));
      throw new InvalidFieldsException(errors);
    }

    Business business = findBusiness(businessId);
    business.updateSettings(
        settings.name(),
        settings.category(),
        settings.defaultLanguage(),
        settings.timezone(),
        writeOpeningHours(settings.openingHours()),
        settings.assistantSettings(),
        settings.handoffSettings().autoAckMinutes(),
        settings.widgetSettings());
    return toSettings(business);
  }

  /** The business of the logged-in membership (tenant isolation: the id comes from PanelGuard). */
  private Business findBusiness(long businessId) {
    return businesses
        .findById(businessId)
        .orElseThrow(
            () -> new NotFoundException("Business not found", "No business with this id."));
  }

  private BusinessSettings toSettings(Business business) {
    return new BusinessSettings(
        business.getName(),
        business.getCategory(),
        business.getDefaultLanguage(),
        business.getTimezone(),
        readOpeningHours(business.getOpeningHours()),
        business.getAssistantSettings(),
        new HandoffSettings(business.autoAckMinutes()),
        business.getWidgetSettings());
  }

  /** JSON body to the record. An unknown field or a wrong type is a field error. */
  private BusinessSettings read(JsonNode body) {
    try {
      BusinessSettings settings = strictReader.treeToValue(body, BusinessSettings.class);
      if (settings == null) {
        throw new InvalidFieldsException(List.of(new FieldError("body", "must be an object")));
      }
      return settings;
    } catch (UnrecognizedPropertyException exception) {
      throw new InvalidFieldsException(List.of(new FieldError(pathOf(exception), "unknown field")));
    } catch (JsonMappingException exception) {
      throw new InvalidFieldsException(
          List.of(new FieldError(pathOf(exception), "wrong type or value")));
    } catch (JsonProcessingException exception) {
      throw new InvalidFieldsException(List.of(new FieldError("body", "not valid JSON")));
    }
  }

  /** e.g. "openingHours.mon[0].open"; "body" when the problem is the whole body. */
  private static String pathOf(JsonMappingException exception) {
    StringBuilder path = new StringBuilder();
    for (JsonMappingException.Reference reference : exception.getPath()) {
      if (reference.getFieldName() != null) {
        if (!path.isEmpty()) {
          path.append('.');
        }
        path.append(reference.getFieldName());
      } else if (reference.getIndex() >= 0) {
        path.append('[').append(reference.getIndex()).append(']');
      }
    }
    return path.isEmpty() ? "body" : path.toString();
  }

  /** "assistantSettings.languages[0].<list element>" gives "assistantSettings.languages[0]". */
  private static String fieldOf(ConstraintViolation<?> violation) {
    return violation.getPropertyPath().toString().replace(".<list element>", "");
  }

  /** An IANA region id such as America/Sao_Paulo; offsets like "+02:00" are not accepted. */
  private static void checkTimezone(String timezone, List<FieldError> errors) {
    if (timezone != null && !ZoneId.getAvailableZoneIds().contains(timezone)) {
      errors.add(new FieldError("timezone", "must be an IANA time zone, e.g. America/Sao_Paulo"));
    }
  }

  private static void checkOpeningHours(
      Map<String, List<TimeRange>> openingHours, List<FieldError> errors) {
    if (openingHours == null) {
      return;
    }
    openingHours.forEach((day, ranges) -> {
      String field = "openingHours." + day;
      if (!DAYS.contains(day)) {
        errors.add(new FieldError(field, "must be one of " + String.join(", ", DAYS)));
        return;
      }
      if (ranges == null) {
        errors.add(new FieldError(field, "must be a list (empty when closed)"));
        return;
      }
      if (ranges.size() > MAX_RANGES_PER_DAY) {
        errors.add(new FieldError(field, "at most " + MAX_RANGES_PER_DAY + " intervals"));
      }
      for (int i = 0; i < ranges.size(); i++) {
        TimeRange range = ranges.get(i);
        String rangeField = field + "[" + i + "]";
        if (range == null) {
          errors.add(new FieldError(rangeField, "must not be null"));
          continue;
        }
        boolean openOk = checkTime(range.open(), rangeField + ".open", errors);
        boolean closeOk = checkTime(range.close(), rangeField + ".close", errors);
        if (openOk && closeOk && range.open().equals(range.close())) {
          errors.add(new FieldError(rangeField, "close must differ from open"));
        }
      }
    });
  }

  private static boolean checkTime(String time, String field, List<FieldError> errors) {
    if (time == null || !TIME.matcher(time).matches()) {
      errors.add(new FieldError(field, "must be a time HH:mm, e.g. 09:30"));
      return false;
    }
    return true;
  }

  /** No language twice, and the default language must be one of the enabled ones. */
  private static void checkLanguages(BusinessSettings settings, List<FieldError> errors) {
    AssistantSettings assistant = settings.assistantSettings();
    if (assistant == null || assistant.languages() == null) {
      return;
    }
    if (new HashSet<>(assistant.languages()).size() != assistant.languages().size()) {
      errors.add(new FieldError("assistantSettings.languages", "must not repeat a language"));
    }
    if (settings.defaultLanguage() != null
        && !assistant.languages().contains(settings.defaultLanguage())) {
      errors.add(new FieldError("defaultLanguage", "must be one of the enabled languages"));
    }
  }

  /**
   * Each allowed domain is an origin, as the browser sends it in the Origin header: http or https,
   * a host and an optional port, nothing else (no path, query or user). A trailing "/" is accepted
   * because the public API ignores it.
   */
  private static void checkAllowedDomains(WidgetSettings widget, List<FieldError> errors) {
    if (widget == null) {
      return;
    }
    List<String> domains = widget.allowedDomains();
    for (int i = 0; i < domains.size(); i++) {
      if (!isOrigin(domains.get(i))) {
        errors.add(
            new FieldError(
                "widgetSettings.allowedDomains[" + i + "]",
                "must be an origin such as https://shop.example.com"));
      }
    }
  }

  private static boolean isOrigin(String value) {
    if (value == null) {
      return false;
    }
    try {
      URI uri = new URI(value);
      String scheme = uri.getScheme();
      String path = uri.getRawPath();
      return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
          && uri.getHost() != null
          && uri.getRawUserInfo() == null
          && (path == null || path.isEmpty() || path.equals("/"))
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null;
    } catch (URISyntaxException exception) {
      return false;
    }
  }

  /**
   * Days from mon to sun, so the response reads like a calendar (jsonb does not keep the order).
   */
  private static Map<String, List<TimeRange>> inWeekOrder(Map<String, List<TimeRange>> days) {
    Map<String, List<TimeRange>> ordered = new LinkedHashMap<>();
    for (String day : DAYS) {
      if (days.containsKey(day)) {
        ordered.put(day, days.get(day));
      }
    }
    return ordered;
  }

  private Map<String, List<TimeRange>> readOpeningHours(String json) {
    try {
      return inWeekOrder(
          objectMapper.readValue(json, new TypeReference<Map<String, List<TimeRange>>>() {}));
    } catch (JsonProcessingException exception) {
      // The column is JSONB, so PostgreSQL already rejected invalid JSON: this should never happen.
      throw new IllegalStateException("Stored opening_hours is not valid JSON", exception);
    }
  }

  private String writeOpeningHours(Map<String, List<TimeRange>> openingHours) {
    try {
      return objectMapper.writeValueAsString(openingHours);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not write opening_hours", exception);
    }
  }
}
