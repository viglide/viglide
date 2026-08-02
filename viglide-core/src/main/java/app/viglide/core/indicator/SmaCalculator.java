package app.viglide.core.indicator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Simple Moving Average — the trailing window arithmetic mean over {@code period} consecutive close
 * prices. Primitive in its own right and used as the middle band of {@link BollingerCalculator}.
 *
 * <p>Algorithm: {@code SMA[i] = mean(prices[i - period + 1 .. i])} for {@code i >= period - 1}. The
 * implementation uses a rolling sum so the per-bar cost is O(1) regardless of the period.
 *
 * <p>Deterministic and side-effect free; identical input always produces identical output (NFR-7).
 */
public final class SmaCalculator {

  private SmaCalculator() {}

  /**
   * Computes SMA({@code period}) over the given close prices.
   *
   * @param prices series of close prices, oldest first; must have at least {@code period} elements
   * @param period lookback period N; must be ≥ 1
   * @return series with {@code offset = period - 1}; {@code values[0]} is the SMA at index {@code
   *     period - 1} of the input
   * @throws IllegalArgumentException if {@code prices.size() < period} or {@code period < 1}
   */
  public static IndicatorSeries calculate(List<BigDecimal> prices, int period) {
    Objects.requireNonNull(prices, "prices");
    if (period < 1) {
      throw new IllegalArgumentException("period must be >= 1, got: " + period);
    }
    if (prices.size() < period) {
      throw new IllegalArgumentException(
          "need at least " + period + " prices for SMA(" + period + "); got " + prices.size());
    }

    BigDecimal n = BigDecimal.valueOf(period);

    // Seed: sum of the first `period` prices.
    BigDecimal sum = BigDecimal.ZERO;
    for (int i = 0; i < period; i++) {
      sum = sum.add(prices.get(i));
    }

    List<BigDecimal> result = new ArrayList<>(prices.size() - period + 1);
    result.add(sum.divide(n, IndicatorMath.MC));

    // Rolling update: drop the oldest, add the newest. O(1) per bar.
    for (int i = period; i < prices.size(); i++) {
      sum = sum.subtract(prices.get(i - period)).add(prices.get(i));
      result.add(sum.divide(n, IndicatorMath.MC));
    }

    return new IndicatorSeries(period - 1, result);
  }
}
