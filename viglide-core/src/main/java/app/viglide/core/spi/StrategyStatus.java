package app.viglide.core.spi;

/**
 * Whether a strategy is eligible to run in an order-placing mode (PLAN-015 Task G). Distinct from
 * {@link StrategyKind}, which describes what data a strategy reads — this describes whether its
 * evidence record supports trusting it with real orders at all, independent of its data source.
 */
public enum StrategyStatus {
  /**
   * Eligible for {@code --mode=testnet} (still subject to every other gate — {@code
   * StrategyKind#FUNDING_AWARE}'s two-leg-executor requirement, the Risk Manager, etc.). The
   * default for {@link StrategyMetadata}'s backward-compatible constructors, so every strategy
   * written before this status existed keeps its prior eligibility unchanged.
   */
  CANDIDATE,

  /**
   * Backtest, calibration, and ablation use only — {@code PaperTradingRunner} refuses {@code
   * --mode=testnet} for a strategy carrying this status (same guard shape as {@code
   * guardFundingAware}, PLAN-012 Task A). Marks a strategy whose own out-of-sample evidence argues
   * against it (e.g. net-negative OOS, or losing to a simpler baseline) without deleting it — it
   * remains a fully valid benchmark/ablation baseline, which is what it is <em>for</em>.
   */
  BENCHMARK_ONLY
}
