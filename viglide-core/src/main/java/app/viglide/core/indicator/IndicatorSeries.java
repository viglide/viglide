package app.viglide.core.indicator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Indicator output aligned to the input series by index. {@code values[k]} corresponds to input
 * index {@code offset + k}, so callers can overlay indicator values onto the original candle array
 * without confusion.
 */
public record IndicatorSeries(int offset, List<BigDecimal> values) {

  /** Validates offset ≥ 0 and defensively copies the value list. */
  public IndicatorSeries {
    Objects.requireNonNull(values, "values");
    if (offset < 0) throw new IllegalArgumentException("offset must be >= 0, got: " + offset);
    if (values.isEmpty()) throw new IllegalArgumentException("values must not be empty");
    values = List.copyOf(values);
  }

  /** Returns the most recent (last) computed value. */
  public BigDecimal last() {
    return values.getLast();
  }

  /**
   * Returns the second-to-last value, needed when comparing the indicator state across two
   * consecutive bars (e.g. to detect a crossover).
   *
   * @throws IllegalStateException if the series has fewer than two values
   */
  public BigDecimal previous() {
    if (values.size() < 2) {
      throw new IllegalStateException(
          "previous() requires at least 2 values; got " + values.size());
    }
    return values.get(values.size() - 2);
  }
}
