package app.viglide.core.data;

import app.viglide.core.domain.Candle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Streams OHLCV candles from a CSV file. Auto-detects two timestamp formats:
 *
 * <ul>
 *   <li><b>Binance kline</b>: column 0 is epoch milliseconds (e.g. {@code 1748736000000}) for USD-M
 *       futures, or epoch <em>microseconds</em> (e.g. {@code 1748736000000000}) for spot —
 *       Binance's spot market data switched to microsecond timestamps while futures/um stayed in
 *       milliseconds (PLAN-008 Task F, discovered joining perp+spot for {@code
 *       FundingArbHarnessV2}). Auto-detected by magnitude: any realistic millisecond epoch for a
 *       date within a few centuries of now stays under {@code 10^14}; the microsecond equivalent is
 *       ~1000x larger. Columns 1-5 are {@code open,high,low,close,volume}; any trailing columns are
 *       ignored.
 *   <li><b>ISO-8601</b>: column 0 is {@link Instant#parse(CharSequence)}-parseable (e.g. {@code
 *       2024-01-01T00:00:00Z}); used by the small committed strategy fixtures.
 * </ul>
 *
 * <p>Header rows and blank lines are skipped defensively: any row whose first column does not parse
 * as either format is ignored. This makes the reader tolerant of merged Binance dumps that contain
 * embedded per-month headers.
 *
 * <p>Stream-based by design — a 525k-row file must not require holding the whole dataset in memory.
 * Callers using {@link #stream(Path)} are responsible for closing the returned stream (use
 * try-with-resources).
 *
 * <p>Deterministic and side-effect free (NFR-7): identical input file ⇒ identical candle stream.
 */
public final class CsvKlineReader {

  private CsvKlineReader() {}

  /**
   * Streams candles from the CSV at {@code path}. The returned stream must be closed by the caller.
   *
   * @throws UncheckedIOException if the file cannot be opened
   * @throws IllegalArgumentException if a non-header row fails to parse
   */
  public static Stream<Candle> stream(Path path) {
    Objects.requireNonNull(path, "path");
    try {
      return Files.lines(path).map(CsvKlineReader::parseOrNull).filter(Objects::nonNull);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to open kline CSV: " + path, e);
    }
  }

  /**
   * Parses a single CSV line into a {@link Candle}, or returns {@code null} for header / blank /
   * unparseable rows. The format of column 0 is detected per-row to keep the reader stateless and
   * tolerant of mixed-format dumps.
   */
  static Candle parseOrNull(String line) {
    if (line == null || line.isBlank()) {
      return null;
    }
    String[] parts = line.split(",");
    if (parts.length < 6) {
      return null;
    }
    String ts = parts[0].trim();
    Instant openTime = parseTimestamp(ts);
    if (openTime == null) {
      return null;
    }
    try {
      return new Candle(
          openTime,
          new BigDecimal(parts[1].trim()),
          new BigDecimal(parts[2].trim()),
          new BigDecimal(parts[3].trim()),
          new BigDecimal(parts[4].trim()),
          new BigDecimal(parts[5].trim()));
    } catch (NumberFormatException e) {
      // Numeric field failed to parse despite the timestamp being valid — treat as data error,
      // not a header row. Fail loudly per CLAUDE.md §5.
      throw new IllegalArgumentException("malformed CSV row: " + line, e);
    }
  }

  /**
   * Millisecond/microsecond disambiguation threshold: any millisecond epoch value for a date within
   * a few centuries of now stays under this; the microsecond equivalent is ~1000x larger.
   */
  private static final long MICROSECOND_EPOCH_THRESHOLD = 100_000_000_000_000L; // 10^14

  /**
   * Auto-detects epoch-ms vs epoch-µs (spot klines) vs ISO-8601. Returns null for header strings.
   */
  private static Instant parseTimestamp(String s) {
    if (s.isEmpty()) return null;
    // Epoch fast path: a positive integer literal, ms or µs (see MICROSECOND_EPOCH_THRESHOLD).
    if (isAllDigits(s)) {
      try {
        long raw = Long.parseLong(s);
        long ms = raw >= MICROSECOND_EPOCH_THRESHOLD ? raw / 1000 : raw;
        return Instant.ofEpochMilli(ms);
      } catch (NumberFormatException e) {
        return null;
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
