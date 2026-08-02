package app.viglide.core.indicator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Textbook Moving Average Convergence Divergence.
 *
 * <p>Algorithm:
 *
 * <ul>
 *   <li>{@code macdLine[i] = EMA(close, fastPeriod)[i] - EMA(close, slowPeriod)[i]}
 *   <li>{@code signalLine[i] = EMA(macdLine, signalPeriod)[i]}
 *   <li>{@code histogram[i] = macdLine[i] - signalLine[i]}
 * </ul>
 *
 * <p>Typical parameters: fast=12, slow=26, signal=9. Output values are aligned to a single shared
 * offset by trimming the head of {@code macdLine} so {@code macd}, {@code signal}, and {@code
 * histogram} all correspond to the same input indices.
 *
 * <p>Deterministic and side-effect free (NFR-7).
 */
public final class MacdCalculator {

  private MacdCalculator() {}

  /**
   * Computes the MACD triple over the given close prices.
   *
   * @param prices series of close prices, oldest first
   * @param fastPeriod fast EMA period (typical: 12)
   * @param slowPeriod slow EMA period (typical: 26); must be strictly greater than {@code
   *     fastPeriod}
   * @param signalPeriod signal-line EMA period (typical: 9)
   * @return aligned {@link MacdSeries}
   * @throws IllegalArgumentException on invalid periods or too few prices
   */
  public static MacdSeries calculate(
      List<BigDecimal> prices, int fastPeriod, int slowPeriod, int signalPeriod) {
    Objects.requireNonNull(prices, "prices");
    if (fastPeriod < 1) {
      throw new IllegalArgumentException("fastPeriod must be >= 1, got: " + fastPeriod);
    }
    if (slowPeriod <= fastPeriod) {
      throw new IllegalArgumentException(
          "slowPeriod (" + slowPeriod + ") must be > fastPeriod (" + fastPeriod + ")");
    }
    if (signalPeriod < 1) {
      throw new IllegalArgumentException("signalPeriod must be >= 1, got: " + signalPeriod);
    }
    int required = slowPeriod + signalPeriod;
    if (prices.size() < required) {
      throw new IllegalArgumentException(
          "need at least "
              + required
              + " prices for MACD("
              + fastPeriod
              + "/"
              + slowPeriod
              + "/"
              + signalPeriod
              + "); got "
              + prices.size());
    }

    IndicatorSeries fastEma = EmaCalculator.calculate(prices, fastPeriod);
    IndicatorSeries slowEma = EmaCalculator.calculate(prices, slowPeriod);

    // Align: fast EMA starts at index fastPeriod-1, slow EMA at slowPeriod-1.  MACD line is
    // defined at every index where BOTH exist, i.e. from slowPeriod-1 onward.  Drop the leading
    // slowPeriod-fastPeriod fast-EMA values so the two series line up index for index.
    int alignSkip = slowPeriod - fastPeriod;
    List<BigDecimal> macdLine = new ArrayList<>(slowEma.values().size());
    for (int i = 0; i < slowEma.values().size(); i++) {
      macdLine.add(fastEma.values().get(i + alignSkip).subtract(slowEma.values().get(i)));
    }

    // Signal line: EMA(signalPeriod) of the MACD line.  Has offset signalPeriod-1 within macdLine.
    IndicatorSeries signalEma = EmaCalculator.calculate(macdLine, signalPeriod);
    List<BigDecimal> signal = signalEma.values();

    // Trim the head of macdLine so it lines up with signal.
    List<BigDecimal> macdTrimmed =
        new ArrayList<>(macdLine.subList(signalPeriod - 1, macdLine.size()));

    // Histogram = trimmed MACD - signal, exact subtraction (no rounding).
    List<BigDecimal> histogram = new ArrayList<>(signal.size());
    for (int i = 0; i < signal.size(); i++) {
      histogram.add(macdTrimmed.get(i).subtract(signal.get(i)));
    }

    int offset = (slowPeriod - 1) + (signalPeriod - 1);
    return new MacdSeries(offset, macdTrimmed, signal, histogram);
  }
}
