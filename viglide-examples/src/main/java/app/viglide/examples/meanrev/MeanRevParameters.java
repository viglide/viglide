package app.viglide.examples.meanrev;

/**
 * Configuration for {@link MeanReversionRsiBbStrategy}.
 *
 * <p>Mean-reversion logic: buy fear (price at lower Bollinger band AND RSI deeply oversold), sell
 * greed (price at upper Bollinger band AND RSI overbought). Textbook defaults are the canonical
 * BB(20, 2.0) + RSI(14) with the standard 30/70 thresholds.
 */
public record MeanRevParameters(
    int rsiPeriod,
    int bbPeriod,
    double bbStdevK,
    double rsiBuyThreshold,
    double rsiSellThreshold,
    double bandTouchTolerance) {

  /** Validates so misconfiguration fails fast at startup. */
  public MeanRevParameters {
    if (rsiPeriod < 2) {
      throw new IllegalArgumentException("rsiPeriod must be >= 2, got: " + rsiPeriod);
    }
    if (bbPeriod < 2) {
      throw new IllegalArgumentException("bbPeriod must be >= 2, got: " + bbPeriod);
    }
    if (bbStdevK <= 0.0) {
      throw new IllegalArgumentException("bbStdevK must be > 0, got: " + bbStdevK);
    }
    if (rsiBuyThreshold <= 0.0
        || rsiBuyThreshold >= rsiSellThreshold
        || rsiSellThreshold >= 100.0) {
      throw new IllegalArgumentException("must satisfy 0 < rsiBuy < rsiSell < 100");
    }
    if (bandTouchTolerance < 0.0 || bandTouchTolerance > 1.0) {
      throw new IllegalArgumentException("bandTouchTolerance must be in [0, 1]");
    }
  }

  /** Textbook defaults: RSI(14) with 30/70 thresholds + BB(20, 2.0) + 2% tolerance. */
  public static MeanRevParameters textbookDefaults() {
    return new MeanRevParameters(14, 20, 2.0, 30.0, 70.0, 0.02);
  }
}
