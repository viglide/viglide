package app.viglide.core.indicator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * MACD output triple aligned to a shared {@code offset}: every index {@code k} of {@code macd},
 * {@code signal}, and {@code histogram} corresponds to input price index {@code offset + k}. The
 * three lists therefore have the same length.
 *
 * <p>Convention: {@code histogram == macd - signal} (exact, no rounding drift, since BigDecimal
 * subtraction is exact).
 */
public record MacdSeries(
    int offset, List<BigDecimal> macd, List<BigDecimal> signal, List<BigDecimal> histogram) {

  /** Validates {@code offset >= 0}, equal lengths, non-empty, and defensively copies the lists. */
  public MacdSeries {
    Objects.requireNonNull(macd, "macd");
    Objects.requireNonNull(signal, "signal");
    Objects.requireNonNull(histogram, "histogram");
    if (offset < 0) throw new IllegalArgumentException("offset must be >= 0, got: " + offset);
    if (macd.isEmpty()) throw new IllegalArgumentException("macd must not be empty");
    if (macd.size() != signal.size() || macd.size() != histogram.size()) {
      throw new IllegalArgumentException(
          "macd, signal and histogram must have equal length; got "
              + macd.size()
              + "/"
              + signal.size()
              + "/"
              + histogram.size());
    }
    macd = List.copyOf(macd);
    signal = List.copyOf(signal);
    histogram = List.copyOf(histogram);
  }

  public BigDecimal lastMacd() {
    return macd.getLast();
  }

  public BigDecimal lastSignal() {
    return signal.getLast();
  }

  public BigDecimal lastHistogram() {
    return histogram.getLast();
  }

  /**
   * Second-to-last MACD value, needed to detect a crossover against the previous bar.
   *
   * @throws IllegalStateException if the series has fewer than two values
   */
  public BigDecimal previousMacd() {
    requireAtLeastTwo();
    return macd.get(macd.size() - 2);
  }

  /** Second-to-last signal value; see {@link #previousMacd()} for the rationale. */
  public BigDecimal previousSignal() {
    requireAtLeastTwo();
    return signal.get(signal.size() - 2);
  }

  private void requireAtLeastTwo() {
    if (macd.size() < 2) {
      throw new IllegalStateException("previous*() requires at least 2 values; got " + macd.size());
    }
  }
}
