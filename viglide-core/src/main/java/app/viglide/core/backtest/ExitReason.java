package app.viglide.core.backtest;

/**
 * Why a position was closed during a backtest. Surfacing the reason lets the trade log distinguish
 * disciplined exits (TP, SL) from strategy-driven exits (signal flip) and end-of-data sweeps.
 */
public enum ExitReason {
  TAKE_PROFIT,
  STOP_LOSS,
  SIGNAL,
  END_OF_DATA,
  /**
   * PLAN-008 Task F: {@code FundingArbHarnessV2}'s liquidation guard force-closed both legs because
   * the perp leg's unrealised loss reached the margin buffer.
   */
  LIQUIDATION_GUARD
}
