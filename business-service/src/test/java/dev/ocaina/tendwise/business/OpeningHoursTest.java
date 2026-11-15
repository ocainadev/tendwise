package dev.ocaina.tendwise.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/** Plain unit test: no Spring, no database. 2026-11-16 is a Monday. */
class OpeningHoursTest {

  private static final String ZONE = "America/Sao_Paulo";
  private static final String WEEKDAYS =
      """
      {"mon": [{"open": "09:00", "close": "19:00"}], "tue": [{"open": "09:00", "close": "19:00"}],
       "wed": [], "thu": [], "fri": [], "sat": [], "sun": []}\
      """;

  private static Instant at(String localDateTime) {
    return ZonedDateTime.parse(localDateTime + "-03:00[" + ZONE + "]").toInstant();
  }

  @Test
  void openInsideAnIntervalAndClosedOutside() {
    OpeningHours hours = OpeningHours.of(WEEKDAYS, ZONE);

    assertThat(hours.isOpenAt(at("2026-11-16T09:00"))).isTrue();
    assertThat(hours.isOpenAt(at("2026-11-16T18:59"))).isTrue();
    assertThat(hours.isOpenAt(at("2026-11-16T19:00"))).isFalse();
    assertThat(hours.isOpenAt(at("2026-11-16T08:59"))).isFalse();
    assertThat(hours.isOpenAt(at("2026-11-18T12:00"))).isFalse(); // Wednesday: []
  }

  @Test
  void missingDayIsClosed() {
    OpeningHours hours =
        OpeningHours.of("{\"mon\": [{\"open\": \"09:00\", \"close\": \"19:00\"}]}", ZONE);

    assertThat(hours.isOpenAt(at("2026-11-17T12:00"))).isFalse(); // Tuesday, not in the JSON
  }

  @Test
  void unknownHoursCountAsAlwaysOpen() {
    OpeningHours hours = OpeningHours.of("{}", ZONE);

    assertThat(hours.isOpenAt(at("2026-11-16T03:00"))).isTrue();
    assertThat(hours.nextOpeningAfter(at("2026-11-16T03:00"))).isEmpty();
  }

  @Test
  void intervalPastMidnightStaysOpenOnTheNextDay() {
    OpeningHours hours =
        OpeningHours.of("{\"fri\": [{\"open\": \"18:00\", \"close\": \"02:00\"}]}", ZONE);

    assertThat(hours.isOpenAt(at("2026-11-20T23:00"))).isTrue(); // Friday night
    assertThat(hours.isOpenAt(at("2026-11-21T01:30"))).isTrue(); // Saturday early morning
    assertThat(hours.isOpenAt(at("2026-11-21T02:00"))).isFalse();
  }

  @Test
  void nextOpeningIsLaterTodayOrOnTheNextOpenDay() {
    OpeningHours hours = OpeningHours.of(WEEKDAYS, ZONE);

    assertThat(hours.nextOpeningAfter(at("2026-11-16T07:00")))
        .contains(ZonedDateTime.parse("2026-11-16T09:00-03:00[" + ZONE + "]"));
    // Tuesday evening: next is the following Monday.
    assertThat(hours.nextOpeningAfter(at("2026-11-17T20:00")))
        .contains(ZonedDateTime.parse("2026-11-23T09:00-03:00[" + ZONE + "]"));
  }

  @Test
  void neverOpenHasNoNextOpening() {
    OpeningHours hours =
        OpeningHours.of(
            "{\"mon\": [], \"tue\": [], \"wed\": [], \"thu\": [], \"fri\": [], \"sat\": [], \"sun\": []}",
            ZONE);

    assertThat(hours.isOpenAt(at("2026-11-16T12:00"))).isFalse();
    assertThat(hours.nextOpeningAfter(at("2026-11-16T12:00"))).isEmpty();
  }
}
