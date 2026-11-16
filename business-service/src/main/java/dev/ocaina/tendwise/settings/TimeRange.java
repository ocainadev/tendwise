package dev.ocaina.tendwise.settings;

/**
 * One open interval of a day in opening_hours, local to the business's time zone, e.g. {"open":
 * "11:00", "close": "22:00"}. A close time before the open time ends on the next day.
 */
public record TimeRange(String open, String close) {}
