package app.viglide.core.indicator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Textbook Exponential Moving Average calculator.
 *
 * <p>Algorithm:
 *
 * <ul>
 *   <li>Smoothing factor α = 2 / (N + 1), computed with {@link IndicatorMath#MC}.
 *   <li>Seed: SMA of the first N values (via {@link java.util.stream.Stream#reduce}).
 *   <li>Recurrence: {@code EMA[i] = price[i] * α + EMA[i-1] * (1 - α)}.
 * </ul>
 *
 * <p>Note: BigDecimal arithmetic dominates the runtime cost; the choice of Stream API vs imperative
 * loop does not change the asymptotic performance. Streams are used here for the seed SMA
 * (idiomatic functional style) and forEach + getLast() for the recurrence (consistent with the Java
 * 21+ SequencedCollection API used elsewhere in viglide-core).
 *
 * <p>Deterministic and side-effect free; identical input always produces identical output (NFR-7).
 */
public final class EmaCalculator {

  private EmaCalculator() {}

  /**
   * Computes EMA(period) over the given close prices.
   *
   * @param prices series of close prices, oldest first; must have at least {@code period} elements
   * @param period lookback period N; must be ≥ 1
   * @return series with {@code offset = period - 1}; {@code values[0]} is the SMA seed
   * @throws IllegalArgumentException if {@code prices.size() < period} or {@code period < 1}
   */
  public static IndicatorSeries calculate(List<BigDecimal> prices, int period) {
    Objects.requireNonNull(prices, "prices");
    if (period < 1) {
      throw new IllegalArgumentException("period must be >= 1, got: " + period);
    }
    if (prices.size() < period) {
      throw new IllegalArgumentException(
          "need at least " + period + " prices for EMA(" + period + "); got " + prices.size());
    }

    BigDecimal alpha = BigDecimal.TWO.divide(BigDecimal.valueOf(period + 1L), IndicatorMath.MC);
    BigDecimal oneMinusAlpha = BigDecimal.ONE.subtract(alpha);

    // Seed: SMA of the first N values using Stream.reduce for a functional, allocation-free sum.
    BigDecimal seedSum =
        prices.subList(0, period).stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal ema = seedSum.divide(BigDecimal.valueOf(period), IndicatorMath.MC);

    // Recurrence: each new EMA depends on the immediately preceding value.  Parallel streams
    // cannot be used here because the operation is inherently sequential.  forEach + getLast()
    // (Java 21+ SequencedList) avoids an index variable while keeping the mutation local.
    List<BigDecimal> result = new ArrayList<>(prices.size() - period + 1);
    result.add(ema);
    prices
        .subList(period, prices.size())
        .forEach(
            price ->
                result.add(
                    price
                        .multiply(alpha, IndicatorMath.MC)
                        .add(
                            result.getLast().multiply(oneMinusAlpha, IndicatorMath.MC),
                            IndicatorMath.MC)));

    return new IndicatorSeries(period - 1, result);
  }
}
