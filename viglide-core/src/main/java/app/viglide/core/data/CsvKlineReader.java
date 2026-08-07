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
 * Streams OHLCV candles from a CSV file. Column 0 is the open time, parsed by {@link CsvTimestamps}
 * (epoch-ms for USD-M futures, epoch-µs for spot, or ISO-8601 for the small committed strategy
 * fixtures); columns 1-5 are {@code open,high,low,close,volume}; any trailing columns are ignored.
 *
 * <p>Header rows and blank lines are skipped defensively: any row whose first column does not parse
 * as a timestamp is ignored. This makes the reader tolerant of merged Binance dumps that contain
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
    Instant openTime = CsvTimestamps.parseOrNull(ts);
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
}
