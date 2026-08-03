package app.viglide.core.data;

import app.viglide.core.domain.FundingEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Streams Binance perpetual-future funding-rate events from a CSV file. Mirrors {@link
 * CsvKlineReader} in spirit: column 0 is parsed by {@link CsvTimestamps} (epoch-ms, epoch-µs or
 * ISO-8601) and per-month headers embedded in merged dumps are tolerated.
 *
 * <p>Expected layout: column 0 is the funding timestamp; column 2 (skipping {@code
 * funding_interval_hours}) is the decimal rate. Two-column variants (time, rate) are also supported
 * — the reader checks column count and falls back gracefully.
 */
public final class CsvFundingReader {

  private CsvFundingReader() {}

  /**
   * Streams funding events from the CSV at {@code path}. Caller closes the stream.
   *
   * @throws UncheckedIOException on I/O failure
   * @throws IllegalArgumentException on malformed rows that are not header-shaped
   */
  public static Stream<FundingEvent> stream(Path path) {
    Objects.requireNonNull(path, "path");
    try {
      return Files.lines(path).map(CsvFundingReader::parseOrNull).filter(Objects::nonNull);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to open funding CSV: " + path, e);
    }
  }

  /** Returns {@code null} for header / blank / unparseable rows. */
  static FundingEvent parseOrNull(String line) {
    if (line == null || line.isBlank()) return null;
    String[] parts = line.split(",");
    if (parts.length < 2) return null;
    Instant time = CsvTimestamps.parseOrNull(parts[0].trim());
    if (time == null) return null;
    int rateIdx = parts.length >= 3 ? 2 : 1;
    try {
      return new FundingEvent(time, new BigDecimal(parts[rateIdx].trim()));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("malformed funding CSV row: " + line, e);
    }
  }
}
