package app.viglide.core.spi;

/**
 * Declares what kind of {@link app.viglide.core.domain.MarketContext} data a {@link
 * TradingStrategy} needs to evaluate meaningfully (F10, PLAN-007 Task D). Backtest/live wiring uses
 * this — not CLI flag presence — to route a strategy to the correct harness: a {@code
 * FUNDING_AWARE} strategy needs {@code FundingArbHarness}'s delta-neutral basis-trade accounting,
 * while {@code OHLCV} strategies (and ensembles of any mix) run on the standard price-exposure
 * harness.
 */
public enum StrategyKind {
  /** Directional strategy driven by price action; no funding-rate dependency. */
  OHLCV,
  /** Delta-neutral basis-carry strategy; requires {@code MarketContext.fundingHistory()}. */
  FUNDING_AWARE
}
