package app.viglide.examples.macdtrend;

/**
 * Configuration for {@link TrendFollowMacdAtrStrategy}.
 *
 * <p>MACD crossover (bullish: MACD crosses above signal line; bearish: MACD crosses below) gated by
 * an ATR-based volatility filter. Designed as the volatile-trend complement to the existing EMA
 * crossover strategy: same direction-detection family but a different math primitive (MACD
 * histogram) with a regime filter (ATR active vs trailing baseline).
 */
public record MacdTrendParameters(
    int macdFast,
    int macdSlow,
    int macdSignal,
    int atrPeriod,
    int atrLookback,
    double atrActiveRatio,
    double histogramScale) {

  /** Validates so misconfiguration fails fast at startup. */
  public MacdTrendParameters {
    if (macdFast < 1) {
      throw new IllegalArgumentException("macdFast must be >= 1, got: " + macdFast);
    }
    if (macdSlow <= macdFast) {
      throw new IllegalArgumentException(
          "macdSlow (" + macdSlow + ") must be > macdFast (" + macdFast + ")");
    }
    if (macdSignal < 1) {
      throw new IllegalArgumentException("macdSignal must be >= 1, got: " + macdSignal);
    }
    if (atrPeriod < 1) {
      throw new IllegalArgumentException("atrPeriod must be >= 1, got: " + atrPeriod);
    }
    if (atrLookback < 1) {
      throw new IllegalArgumentException("atrLookback must be >= 1, got: " + atrLookback);
    }
    if (atrActiveRatio <= 0.0) {
      throw new IllegalArgumentException("atrActiveRatio must be > 0, got: " + atrActiveRatio);
    }
    if (histogramScale <= 0.0) {
      throw new IllegalArgumentException("histogramScale must be > 0, got: " + histogramScale);
    }
  }

  /** Textbook defaults: MACD(12,26,9), ATR(14) with 60-bar baseline at ratio 1.0. */
  public static MacdTrendParameters textbookDefaults() {
    return new MacdTrendParameters(12, 26, 9, 14, 60, 1.0, 0.5);
  }
}
