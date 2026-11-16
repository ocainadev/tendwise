"""Is the business open right now, and when does it open next?

Opening hours come from business-service (`BusinessInfo.opening_hours`), in the
business's own time zone, e.g. {"tue": [{"open": "11:00", "close": "22:00"}],
"mon": []}. A close time before the open time ends on the next day (a bar open
18:00–02:00). Unknown hours (`None` or `{}`) count as "always open", which is
what the agent did before T54.
"""

import logging
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

logger = logging.getLogger(__name__)

# Python's date.weekday(): Monday is 0.
DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
# How far ahead to look for the next opening: a whole week and a day.
DAYS_AHEAD = 8

OpeningHours = dict[str, list[dict[str, str]]]


def _intervals(opening_hours: OpeningHours, zone: ZoneInfo, today: date) -> list[tuple]:
    """Every (start, end) opening interval from yesterday to a week ahead, as aware datetimes.

    Yesterday is included because an interval that starts yesterday evening
    may still be open after midnight.
    """
    intervals = []
    for offset in range(-1, DAYS_AHEAD):
        day = today + timedelta(days=offset)
        for interval in opening_hours.get(DAYS[day.weekday()], []):
            opens = time.fromisoformat(interval["open"])
            closes = time.fromisoformat(interval["close"])
            start = datetime.combine(day, opens, tzinfo=zone)
            end = datetime.combine(day, closes, tzinfo=zone)
            if closes <= opens:
                end += timedelta(days=1)
            intervals.append((start, end))
    return intervals


def closed_until(
    now: datetime, timezone: str, opening_hours: OpeningHours | None
) -> tuple[bool, datetime | None]:
    """(closed, next opening) at the aware datetime `now`.

    `closed` is False when the business is open or its hours are unknown or
    unreadable (then the agent behaves as before T54). The next opening is in
    the business's time zone, or None when it is closed with no opening in
    the next week.
    """
    if not opening_hours:
        return False, None
    try:
        zone = ZoneInfo(timezone)
        local_now = now.astimezone(zone)
        intervals = _intervals(opening_hours, zone, local_now.date())
    except (ZoneInfoNotFoundError, KeyError, TypeError, ValueError) as error:
        # business-service validates the hours (T53), so this is a bug or an old row.
        logger.warning("Unreadable opening hours, treating as open: %s", type(error).__name__)
        return False, None

    if any(start <= local_now < end for start, end in intervals):
        return False, None
    starts = [start for start, _ in intervals if start > local_now]
    return True, min(starts) if starts else None
