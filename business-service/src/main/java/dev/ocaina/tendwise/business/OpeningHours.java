package dev.ocaina.tendwise.business;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A business's weekly opening hours, read from {@code businesses.opening_hours}, e.g. {"mon":
 * [{"open": "11:00", "close": "23:00"}], "sun": []}. Times are local to the business's time zone.
 *
 * <ul>
 *   <li>A day with an empty list, or missing from a non-empty JSON, is closed all day.
 *   <li>An empty JSON ({}) means the hours are unknown: the business counts as always open, so
 *       nobody is told "we are closed" by mistake.
 *   <li>A close time at or before the open time ends on the next day (e.g. 18:00 to 02:00).
 * </ul>
 */
public final class OpeningHours {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> DAY_KEYS =
      List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");

  /** One open interval of a day, as written in the JSON. */
  record Interval(String open, String close) {}

  /** An interval placed on a date: open from start (included) to end (excluded). */
  private record Period(ZonedDateTime start, ZonedDateTime end) {}

  private final Map<String, List<Interval>> days;
  private final ZoneId zone;

  private OpeningHours(Map<String, List<Interval>> days, ZoneId zone) {
    this.days = days;
    this.zone = zone;
  }

  /** Parses the JSON column. Invalid JSON is a data error and throws. */
  public static OpeningHours of(String json, String timezone) {
    try {
      Map<String, List<Interval>> days =
          json == null || json.isBlank()
              ? Map.of()
              : JSON.readValue(json, new TypeReference<Map<String, List<Interval>>>() {});
      return new OpeningHours(days, ZoneId.of(timezone));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Invalid opening_hours JSON", e);
    }
  }

  public static OpeningHours of(Business business) {
    return of(business.getOpeningHours(), business.getTimezone());
  }

  /** True when the instant falls inside an open interval (or the hours are unknown). */
  public boolean isOpenAt(Instant instant) {
    if (days.isEmpty()) {
      return true;
    }
    ZonedDateTime now = instant.atZone(zone);
    // Yesterday's late interval (e.g. 18:00 to 02:00) can still be open today.
    for (LocalDate date : List.of(now.toLocalDate().minusDays(1), now.toLocalDate())) {
      for (Period period : periodsOf(date)) {
        if (!now.isBefore(period.start()) && now.isBefore(period.end())) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * The next time the business opens after the instant, within a week. Empty when the hours are
   * unknown or the business never opens.
   */
  public Optional<ZonedDateTime> nextOpeningAfter(Instant instant) {
    ZonedDateTime now = instant.atZone(zone);
    for (int day = 0; day <= 7; day++) {
      Optional<ZonedDateTime> first =
          periodsOf(now.toLocalDate().plusDays(day)).stream()
              .map(Period::start)
              .filter(start -> start.isAfter(now))
              .min(ZonedDateTime::compareTo);
      if (first.isPresent()) {
        return first;
      }
    }
    return Optional.empty();
  }

  /** The open periods that start on this date. */
  private List<Period> periodsOf(LocalDate date) {
    String key = DAY_KEYS.get(date.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
    List<Period> periods = new ArrayList<>();
    for (Interval interval : days.getOrDefault(key, List.of())) {
      LocalTime open = LocalTime.parse(interval.open());
      LocalTime close = LocalTime.parse(interval.close());
      ZonedDateTime start = date.atTime(open).atZone(zone);
      ZonedDateTime end =
          (close.isAfter(open) ? date : date.plusDays(1)).atTime(close).atZone(zone);
      periods.add(new Period(start, end));
    }
    return periods;
  }
}
