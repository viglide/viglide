package app.viglide.core.backtest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One forced close by {@link PortfolioFundingArbHarnessV2}'s per-symbol liquidation guard
 * (PLAN-019 Task A — liquidation-rate triage needs per-event attribution that {@link
 * BacktestResult#trades()} alone cannot give, since {@link Trade} carries no symbol). Surfaced via
 * {@code diagnostics().get("liquidationEvents")}, purely additive — existing callers reading other
 * diagnostics keys are unaffected.
 *
 * @param symbol the position that was liquidated
 * @param time the bar (or sub-bar, when sub-bar candles are supplied) at which the guard fired
 * @param perpLoss the perp leg's unrealised loss that tripped the guard
 * @param marginThreshold the margin-buffer threshold that was breached ({@code margin ×
 *     LIQUIDATION_MARGIN_BUFFER})
 * @param overshoot how far {@code perpLoss} exceeded {@code marginThreshold} at the moment the
 *     guard checked — a large overshoot at 1h close-only cadence is evidence the guard is catching
 *     the breach late relative to intrabar price action, not that it failed to fire
 * @param bookNotionalAtLiquidation every symbol's mark-to-market notional summed at this instant
 *     (this symbol's own current notional plus every other symbol's last-marked notional) — answers
 *     whether book-level leverage, not just this position's own leverage, was implicated
 * @param equityAtLiquidation portfolio equity (cash + every open position's mark) at this instant
 * @param bookLeverageAtLiquidation {@code bookNotionalAtLiquidation / equityAtLiquidation}, or
 *     {@code 0} when equity is non-positive
 */
public record LiquidationEvent(
    String symbol,
    Instant time,
    BigDecimal perpLoss,
    BigDecimal marginThreshold,
    BigDecimal overshoot,
    BigDecimal bookNotionalAtLiquidation,
    BigDecimal equityAtLiquidation,
    double bookLeverageAtLiquidation) {

  public LiquidationEvent {
    Objects.requireNonNull(symbol, "symbol");
    Objects.requireNonNull(time, "time");
    Objects.requireNonNull(perpLoss, "perpLoss");
    Objects.requireNonNull(marginThreshold, "marginThreshold");
    Objects.requireNonNull(overshoot, "overshoot");
    Objects.requireNonNull(bookNotionalAtLiquidation, "bookNotionalAtLiquidation");
    Objects.requireNonNull(equityAtLiquidation, "equityAtLiquidation");
  }
}
