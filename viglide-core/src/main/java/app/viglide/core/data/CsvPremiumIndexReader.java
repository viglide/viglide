package app.viglide.core.data;

import app.viglide.core.domain.PremiumIndexEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Streams Binance premium-index samples from a CSV file. The on-disk shape is Binance's {@code
 * premiumIndexKlines} bulk-download product — identical column layout to a regular kline ({@code
 * open_time,open,high,low,close,volume,...}) with the premium-index value (a decimal fraction, e.g.
 * {@code 0.00068158}) in place of price and a meaningless zero {@code volume}. Mirrors {@link
 * CsvKlineReader} and {@link CsvFundingReader} in spirit: auto-detects epoch-ms vs ISO-8601
 * timestamps and tolerates per-month headers embedded in merged dumps.
 *
 * <p>Only the {@code close} column is read — the last premium-index sample observed within that
 * bar's window, which is what a decision made <em>at</em> that bar's close would actually have
 * seen. This mirrors how every other strategy in this codebase reads "the current value" off a
 * candle.
 */
public final class CsvPremiumIndexReader {

  private CsvPremiumIndexReader() {}

  /**
   * Streams premium-index events from the CSV at {@code path}. Caller closes the stream.
   *
   * @throws UncheckedIOException on I/O failure
   * @throws IllegalArgumentException on malformed rows that are not header-shaped
   */
  public static Stream<PremiumIndexEvent> stream(Path path) {
    Objects.requireNonNull(path, "path");
    try {
      return Files.lines(path).map(CsvPremiumIndexReader::parseOrNull).filter(Objects::nonNull);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to open premium-index CSV: " + path, e);
    }
  }

  /** Returns {@code null} for header / blank / unparseable rows. */
  static PremiumIndexEvent parseOrNull(String line) {
    if (line == null || line.isBlank()) return null;
    String[] parts = line.split(",");
    if (parts.length < 5) return null;
    Instant time = parseTimestamp(parts[0].trim());
    if (time == null) return null;
    try {
      return new PremiumIndexEvent(time, new BigDecimal(parts[4].trim()));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("malformed premium-index CSV row: " + line, e);
    }
  }

  private static final long MICROSECOND_EPOCH_THRESHOLD = 100_000_000_000_000L; // 10^14

  private static Instant parseTimestamp(String s) {
    if (s.isEmpty()) return null;
    if (isAllDigits(s)) {
      try {
        long raw = Long.parseLong(s);
        long ms = raw >= MICROSECOND_EPOCH_THRESHOLD ? raw / 1000 : raw;
        return Instant.ofEpochMilli(ms);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    try {
      return Instant.parse(s);
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
  }

  private static boolean isAllDigits(String s) {
    for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
    return true;
  }
}
