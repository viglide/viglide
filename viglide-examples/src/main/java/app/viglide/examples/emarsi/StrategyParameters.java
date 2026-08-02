package app.viglide.examples.emarsi;

/**
 * Configuration for {@link EmaCrossoverRsiStrategy}.
 *
 * <p>Supplied by the runtime at startup. The values here are textbook defaults for the POC; tuned
 * production values (the actual alpha) come from GCP Secret Manager at runtime and are never
 * committed to source (CLAUDE.md §8, ADR-0001 §4).
 */
public record StrategyParameters(
    int emaFast,
    int emaSlow,
    int rsiPeriod,
    double rsiOverbought,
    double rsiOversold,
    double spreadScale) {

  /**
   * Validates ordering constraints so misconfiguration fails fast at startup, not during trading.
   */
  public StrategyParameters {
    if (emaFast < 2) throw new IllegalArgumentException("emaFast must be >= 2, got: " + emaFast);
    if (emaSlow < 2) throw new IllegalArgumentException("emaSlow must be >= 2, got: " + emaSlow);
    if (emaFast >= emaSlow)
      throw new IllegalArgumentException(
          "emaFast (" + emaFast + ") must be < emaSlow (" + emaSlow + ")");
    if (rsiPeriod < 2)
      throw new IllegalArgumentException("rsiPeriod must be >= 2, got: " + rsiPeriod);
    if (rsiOversold <= 0.0 || rsiOversold >= rsiOverbought || rsiOverbought >= 100.0) {
      throw new IllegalArgumentException("must satisfy 0 < rsiOversold < rsiOverbought < 100");
    }
    if (spreadScale <= 0.0) {
      throw new IllegalArgumentException("spreadScale must be > 0, got: " + spreadScale);
    }
  }

  /**
   * Textbook defaults: EMA 9/21, RSI 14, overbought/oversold 70/30. These are NOT the tuned alpha —
   * they are the canonical textbook values (ADR-0001 §4).
   */
  public static StrategyParameters textbookDefaults() {
    return new StrategyParameters(9, 21, 14, 70.0, 30.0, 0.01);
  }
}
