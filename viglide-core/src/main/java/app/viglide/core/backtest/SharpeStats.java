package app.viglide.core.backtest;

/**
 * Distribution statistics of the daily-resampled equity-curve returns (PLAN-008 Task C). This is
 * the shared basis for the headline Sharpe ratio and for the Probabilistic/Deflated Sharpe Ratio
 * (see {@link Metrics#probabilisticSharpeRatio} / {@link Metrics#deflatedSharpeRatio}).
 *
 * <p>{@code skewness}/{@code kurtosis} use central moments with {@code 1/n} ("population")
 * normalisation, deliberately different from {@code perPeriodSharpe}'s {@code 1/(n-1)} sample
 * standard deviation — this mixed convention matches Bailey &amp; López de Prado (2012)'s PSR
 * derivation, which is the reason these statistics exist.
 *
 * <p>Degenerate inputs (fewer than 2 return observations, or zero return variance) yield an
 * all-zero instance with {@link #observations()} still set to the true count.
 *
 * <p><strong>{@link #observations()} counts days, not trades</strong> (PLAN-010 D10-4, finding F5)
 * — it is the length of the daily-resampled return series {@link Metrics#sharpeStats} builds, so a
 * strategy that made a handful of trades across a full backtest year can still show a large {@code
 * observations()} (T≈365) even though the actual evidence behind the Sharpe/PSR/DSR built from it
 * is thin. Always read this figure alongside the trade count; see {@link Metrics}'s class Javadoc
 * and {@link Metrics#MIN_TRADES_FOR_STATS} for the full caveat.
 */
public record SharpeStats(
    double perPeriodSharpe,
    double annualisedSharpe,
    int observations,
    double skewness,
    double kurtosis) {

  /**
   * Days per year used to annualise crypto's 24/7 markets. {@code public} (PLAN-011 Task J / F10)
   * so {@code viglide-strategies}' {@code CalibrationHarness} can share this constant instead of
   * redeclaring its own copy — {@link Metrics} (same package) already did.
   */
  public static final double ANNUALISATION_FACTOR = Math.sqrt(365.0);

  /** All-zero instance for degenerate inputs, preserving the real observation count. */
  static SharpeStats degenerate(int observations) {
    return new SharpeStats(0.0, 0.0, observations, 0.0, 0.0);
  }
}
