package app.viglide.core.data;

import java.time.Instant;

/**
 * Shared timestamp parsing for this package's CSV readers (PLAN-015 Task C review). Every reader
 * here faces the same column-0 problem, and each used to carry its own copy of this logic — three
 * copies that had already drifted apart: {@link CsvFundingReader} was missing the microsecond
 * branch entirely, so a funding dump in Binance's µs format parsed to year-56000 timestamps, sorted
 * after every candle, and silently accrued no funding at all.
 *
 * <p>Two formats are auto-detected:
 *
 * <ul>
 *   <li><b>Epoch integer</b>: milliseconds (e.g. {@code 1748736000000}) for USD-M futures, or
 *       <em>microseconds</em> (e.g. {@code 1748736000000000}) for spot — Binance's spot market data
 *       switched to microsecond timestamps while futures/um stayed in milliseconds (PLAN-008 Task
 *       F, discovered joining perp+spot for {@code FundingArbHarnessV2}). Disambiguated by
 *       magnitude: any realistic millisecond epoch for a date within a few centuries of now stays
 *       under {@code 10^14}; the microsecond equivalent is ~1000x larger.
 *   <li><b>ISO-8601</b>: {@link Instant#parse(CharSequence)}-parseable (e.g. {@code
 *       2024-01-01T00:00:00Z}); used by the small committed strategy fixtures.
 * </ul>
 *
 * <p>Anything else yields {@code null}, which every caller treats as "header or blank row, skip it"
 * — this is what makes the readers tolerant of merged Binance dumps carrying embedded per-month
 * headers.
 */
final class CsvTimestamps {

  private CsvTimestamps() {}

  /**
   * Millisecond/microsecond disambiguation threshold: any millisecond epoch value for a date within
   * a few centuries of now stays under this; the microsecond equivalent is ~1000x larger.
   */
  private static final long MICROSECOND_EPOCH_THRESHOLD = 100_000_000_000_000L; // 10^14

  /** Parses epoch-ms / epoch-µs / ISO-8601, or returns {@code null} for a header-shaped string. */
  static Instant parseOrNull(String s) {
    if (s == null || s.isEmpty()) return null;
    // Epoch fast path: a positive integer literal, ms or µs (see MICROSECOND_EPOCH_THRESHOLD).
    if (isAllDigits(s)) {
      try {
        long raw = Long.parseLong(s);
        long ms = raw >= MICROSECOND_EPOCH_THRESHOLD ? raw / 1000 : raw;
        return Instant.ofEpochMilli(ms);
      } catch (NumberFormatException e) {
        return null; // overflows a long — not a timestamp we can use
      }
    }
    // ISO-8601 path.
    try {
      return Instant.parse(s);
    } catch (java.time.format.DateTimeParseException e) {
      return null; // header row like "timestamp" or "open_time"
    }
  }

  private static boolean isAllDigits(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (!Character.isDigit(s.charAt(i))) return false;
    }
    return true;
  }
}
