package app.viglide.core.indicator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bollinger Bands. Middle band is the {@code period}-bar SMA; upper and lower bands are the middle
 * ± {@code k} · (sample stddev of the same window).
 *
 * <p>Standard parameters: period=20, k=2.0. Sample standard deviation uses the {@code n − 1}
 * denominator (Bessel-corrected) to match every textbook definition. The square root is computed
 * via {@link BigDecimal#sqrt(java.math.MathContext)} so we keep the BigDecimal precision contract.
 *
 * <p>Deterministic and side-effect free (NFR-7).
 */
public final class BollingerCalculator {

  private BollingerCalculator() {}

  /**
   * Computes Bollinger Bands over the given close prices.
   *
   * @param prices series of close prices, oldest first
   * @param period lookback period N (typical: 20); must be ≥ 2 (stddev needs at least two samples)
   * @param k band multiplier (typical: 2.0); must be ≥ 0
   * @return aligned {@link BollingerSeries}
   * @throws IllegalArgumentException on invalid parameters or too few prices
   */
  public static BollingerSeries calculate(List<BigDecimal> prices, int period, double k) {
    Objects.requireNonNull(prices, "prices");
    if (period < 2) {
      throw new IllegalArgumentException("period must be >= 2, got: " + period);
    }
    if (k < 0.0) {
      throw new IllegalArgumentException("k must be >= 0, got: " + k);
    }
    if (prices.size() < period) {
      throw new IllegalArgumentException(
          "need at least "
              + period
              + " prices for Bollinger("
              + period
              + "); got "
              + prices.size());
    }

    BigDecimal kBd = BigDecimal.valueOf(k);
    BigDecimal n = BigDecimal.valueOf(period);
    BigDecimal nMinus1 = BigDecimal.valueOf(period - 1L);

    int outLen = prices.size() - period + 1;
    List<BigDecimal> middle = new ArrayList<>(outLen);
    List<BigDecimal> upper = new ArrayList<>(outLen);
    List<BigDecimal> lower = new ArrayList<>(outLen);

    for (int end = period - 1; end < prices.size(); end++) {
      int start = end - period + 1;
      // SMA over [start..end]
      BigDecimal sum = BigDecimal.ZERO;
      for (int i = start; i <= end; i++) sum = sum.add(prices.get(i));
      BigDecimal mean = sum.divide(n, IndicatorMath.MC);

      // Sample variance: Σ(price - mean)² / (n - 1)
      BigDecimal sumSq = BigDecimal.ZERO;
      for (int i = start; i <= end; i++) {
        BigDecimal d = prices.get(i).subtract(mean);
        sumSq = sumSq.add(d.multiply(d, IndicatorMath.MC));
      }
      BigDecimal variance = sumSq.divide(nMinus1, IndicatorMath.MC);
      BigDecimal stdev = variance.sqrt(IndicatorMath.MC);
      BigDecimal band = stdev.multiply(kBd, IndicatorMath.MC);

      middle.add(mean);
      upper.add(mean.add(band, IndicatorMath.MC));
      lower.add(mean.subtract(band, IndicatorMath.MC));
    }

    return new BollingerSeries(period - 1, middle, upper, lower);
  }
}
