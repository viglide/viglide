package app.viglide.core.indicator;

import app.viglide.core.domain.Candle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Average True Range using Wilder's smoothing — measures market volatility independent of
 * direction. ATR is bounded below by zero and is expressed in the same units as the underlying
 * price.
 *
 * <p>Algorithm:
 *
 * <ul>
 *   <li>True Range: {@code TR[i] = max(high[i] - low[i], |high[i] - close[i-1]|, |low[i] -
 *       close[i-1]|)} for {@code i ≥ 1}. (The first candle has no {@code close[i-1]}, so no TR.)
 *   <li>Seed at bar {@code N}: {@code ATR[N] = mean(TR[1..N])}.
 *   <li>Wilder smoothing for {@code i > N}: {@code ATR[i] = (ATR[i-1] * (N-1) + TR[i]) / N}.
 * </ul>
 *
 * <p>Deterministic and side-effect free; identical input always produces identical output (NFR-7).
 */
public final class AtrCalculator {

  private AtrCalculator() {}

  /**
   * Computes ATR({@code period}) over the given candles.
   *
   * @param candles series of OHLCV candles, oldest first
   * @param period lookback period N (typical: 14); must be ≥ 1
   * @return series with {@code offset = period}; all values are non-negative
   * @throws IllegalArgumentException if {@code candles.size() < period + 1} or {@code period < 1}
   */
  public static IndicatorSeries calculate(List<Candle> candles, int period) {
    Objects.requireNonNull(candles, "candles");
    if (period < 1) {
      throw new IllegalArgumentException("period must be >= 1, got: " + period);
    }
    int required = period + 1;
    if (candles.size() < required) {
      throw new IllegalArgumentException(
          "need at least " + required + " candles for ATR(" + period + "); got " + candles.size());
    }

    BigDecimal n = BigDecimal.valueOf(period);
    BigDecimal nMinus1 = BigDecimal.valueOf(period - 1L);

    int trCount = candles.size() - 1;
    BigDecimal[] tr = new BigDecimal[trCount];
    for (int i = 0; i < trCount; i++) {
      Candle prev = candles.get(i);
      Candle curr = candles.get(i + 1);
      BigDecimal hl = curr.high().subtract(curr.low());
      BigDecimal hc = curr.high().subtract(prev.close()).abs();
      BigDecimal lc = curr.low().subtract(prev.close()).abs();
      tr[i] = hl.max(hc).max(lc);
    }

    // Seed: SMA of the first `period` TR values.
    BigDecimal sum = BigDecimal.ZERO;
    for (int i = 0; i < period; i++) {
      sum = sum.add(tr[i]);
    }
    BigDecimal atr = sum.divide(n, IndicatorMath.MC);

    List<BigDecimal> result = new ArrayList<>(trCount - period + 1);
    result.add(atr);

    // Wilder smoothing.
    for (int i = period; i < trCount; i++) {
      atr =
          atr.multiply(nMinus1, IndicatorMath.MC)
              .add(tr[i], IndicatorMath.MC)
              .divide(n, IndicatorMath.MC);
      result.add(atr);
    }

    return new IndicatorSeries(period, result);
  }
}
