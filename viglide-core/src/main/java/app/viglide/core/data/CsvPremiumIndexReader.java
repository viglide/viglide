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
 * CsvKlineReader} and {@link CsvFundingReader} in spirit: column 0 is parsed by {@link
 * CsvTimestamps} (epoch-ms, epoch-µs or ISO-8601) and per-month headers embedded in merged dumps
 * are tolerated.
 *
 * <p><strong>Timestamp semantics matter here.</strong> Only the {@code close} column is read — the
 * last premium-index sample within that kline's window — but the emitted event is stamped with the
 * kline's {@code open_time}, mirroring how {@link CsvKlineReader} keys a candle. The value a row
 * carries is therefore only observable at {@code open_time + samplingInterval}, not at {@code
 * open_time}.
 *
 * <p>The consequence: a series read by this class is lookahead-free only while it is sampled at
 * least as finely as the backtest's decision bars, because the harnesses admit rows at or before a
 * bar's {@code openTime} and then show the strategy that same bar's close. {@code
 * FundingArbHarnessV2.validatePremiumIndexSeries} enforces exactly that precondition (and ascending
 * order) once per run, so a mismatched download fails loudly instead of quietly back-dating future
 * information.
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
    Instant time = CsvTimestamps.parseOrNull(parts[0].trim());
    if (time == null) return null;
    try {
      return new PremiumIndexEvent(time, new BigDecimal(parts[4].trim()));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("malformed premium-index CSV row: " + line, e);
    }
  }
}
