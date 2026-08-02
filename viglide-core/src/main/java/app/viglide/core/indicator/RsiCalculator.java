package app.viglide.core.indicator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Textbook Relative Strength Index using Wilder's smoothing.
 *
 * <p>Algorithm:
 *
 * <ul>
 *   <li>Deltas: {@code d[i] = close[i] − close[i-1]}; gain = max(d, 0), loss = max(−d, 0).
 *   <li>Seed at bar N: {@code avgGain = mean(gains[0..N-1])}, {@code avgLoss =
 *       mean(losses[0..N-1])}.
 *   <li>Wilder smoothing for bar i > N: {@code avgGain = (prevAvgGain * (N-1) + gain[i]) / N}; same
 *       for avgLoss.
 *   <li>RS = avgGain / avgLoss; RSI = 100 − 100 / (1 + RS).
 *   <li>Edge cases: avgLoss == 0 → RSI = 100; avgGain == 0 → RSI = 0.
 * </ul>
 *
 * <p>Deterministic and side-effect free; identical input always produces identical output (NFR-7).
 */
public final class RsiCalculator {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private RsiCalculator() {}

  /**
   * Computes RSI(period) over the given close prices.
   *
   * @param prices series of close prices, oldest first; must have at least {@code period + 1}
   *     elements
   * @param period lookback period N (e.g. 14); must be ≥ 1
   * @return series with {@code offset = period}; all values are in [0, 100]
   * @throws IllegalArgumentException if {@code prices.size() < period + 1} or {@code period < 1}
   */
  public static IndicatorSeries calculate(List<BigDecimal> prices, int period) {
    Objects.requireNonNull(prices, "prices");
    if (period < 1) {
      throw new IllegalArgumentException("period must be >= 1, got: " + period);
    }
    int required = period + 1;
    if (prices.size() < required) {
      throw new IllegalArgumentException(
          "need at least " + required + " prices for RSI(" + period + "); got " + prices.size());
    }

    BigDecimal n = BigDecimal.valueOf(period);
    BigDecimal nMinus1 = BigDecimal.valueOf(period - 1L);

    // Compute all price deltas: delta[i] = prices[i+1] - prices[i]
    int deltaCount = prices.size() - 1;
    BigDecimal[] gains = new BigDecimal[deltaCount];
    BigDecimal[] losses = new BigDecimal[deltaCount];
    for (int i = 0; i < deltaCount; i++) {
      BigDecimal delta = prices.get(i + 1).subtract(prices.get(i));
      gains[i] = delta.signum() > 0 ? delta : BigDecimal.ZERO;
      losses[i] = delta.signum() < 0 ? delta.negate() : BigDecimal.ZERO;
    }

    // Seed: SMA of the first N gains and losses (deltas[0..N-1])
    BigDecimal sumGain = BigDecimal.ZERO;
    BigDecimal sumLoss = BigDecimal.ZERO;
    for (int i = 0; i < period; i++) {
      sumGain = sumGain.add(gains[i]);
      sumLoss = sumLoss.add(losses[i]);
    }
    BigDecimal avgGain = sumGain.divide(n, IndicatorMath.MC);
    BigDecimal avgLoss = sumLoss.divide(n, IndicatorMath.MC);

    List<BigDecimal> result = new ArrayList<>(deltaCount - period + 1);
    result.add(rsiValue(avgGain, avgLoss));

    // Wilder smoothing for all subsequent bars
    for (int i = period; i < deltaCount; i++) {
      avgGain =
          avgGain
              .multiply(nMinus1, IndicatorMath.MC)
              .add(gains[i], IndicatorMath.MC)
              .divide(n, IndicatorMath.MC);
      avgLoss =
          avgLoss
              .multiply(nMinus1, IndicatorMath.MC)
              .add(losses[i], IndicatorMath.MC)
              .divide(n, IndicatorMath.MC);
      result.add(rsiValue(avgGain, avgLoss));
    }

    return new IndicatorSeries(period, result);
  }

  /** Applies the RSI formula with edge-case guards for zero avgGain or avgLoss. */
  private static BigDecimal rsiValue(BigDecimal avgGain, BigDecimal avgLoss) {
    if (avgLoss.signum() == 0) return HUNDRED; // no losses → RSI = 100
    if (avgGain.signum() == 0) return BigDecimal.ZERO; // no gains  → RSI = 0
    BigDecimal rs = avgGain.divide(avgLoss, IndicatorMath.MC);
    return HUNDRED.subtract(
        HUNDRED.divide(BigDecimal.ONE.add(rs, IndicatorMath.MC), IndicatorMath.MC),
        IndicatorMath.MC);
  }
}
