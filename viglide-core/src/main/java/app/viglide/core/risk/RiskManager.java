package app.viglide.core.risk;

import app.viglide.core.domain.Direction;
import app.viglide.core.domain.ExchangeFilters;
import app.viglide.core.domain.Factor;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.indicator.AtrCalculator;
import app.viglide.core.indicator.IndicatorMath;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Concrete Layer-4 Risk Manager (PLAN-005b, ADR-0006/ADR-0007, CLAUDE.md §7). Enforces the hard
 * limits as a sequential gate: first refusal short-circuits. Approved signals get ATR-derived
 * sizing and a mandatory stop-loss price.
 *
 * <p>Note: Per ADR-0007, this class is a permanent, intentional part of the PUBLIC {@code
 * viglide-core} — not a tactical deviation. It has zero framework dependencies and zero proprietary
 * parameters (limits are injected via {@link RiskParameters}); {@code viglide-runtime} owns
 * enforcement (wiring, the live decision loop, and the ArchUnit no-bypass guard).
 *
 * <p>The stop-loss price/distance computed below is returned on every {@link
 * ExecutionDecision.Execute} uniformly, but it is only economically meaningful for a directional
 * (OHLCV) position — a caller must treat it as advisory, not authoritative, for a {@code
 * FUNDING_AWARE} signal's price-neutral delta-neutral position (ADR-0015, PLAN-012 Task E).
 *
 * <p>Gate order:
 *
 * <ol>
 *   <li>HOLD signals → refuse immediately ({@link ExecutionDecision.RefusalReason#HOLD_SIGNAL}).
 *   <li>Circuit breaker tripped → refuse.
 *   <li>Daily loss limit reached (PLAN-012 Task F, opt-in — only when both {@link
 *       RiskParameters#maxDailyLossAbs()} and {@link PortfolioState#dayStartEquity()} are present)
 *       → refuse ({@link ExecutionDecision.RefusalReason#DAILY_LOSS_LIMIT_REACHED}).
 *   <li>Confidence below floor → refuse.
 *   <li>Signal older than {@link RiskParameters#maxStaleInputAge()} → refuse ({@link
 *       ExecutionDecision.RefusalReason#STALE_INPUT}).
 *   <li>SELL with no open long position to close → refuse ({@link
 *       ExecutionDecision.RefusalReason#NO_OPEN_POSITION_TO_CLOSE}).
 *   <li>Insufficient candles for ATR → refuse ({@link
 *       ExecutionDecision.RefusalReason#STALE_INPUT}).
 *   <li>ATR is zero → refuse ({@link ExecutionDecision.RefusalReason#STOP_LOSS_UNDERIVABLE}).
 *   <li>Compute size from risk-per-trade and ATR stop-loss distance; cap at 2% of equity.
 *   <li>Absolute per-position / total-deployed caps (PLAN-012 Task F, opt-in) → refuse ({@link
 *       ExecutionDecision.RefusalReason#ABSOLUTE_EXPOSURE_CAP_EXCEEDED}).
 *   <li>Leverage cap: if new position notional would exceed {@code equity × maxLeverage} → refuse.
 *   <li>Daily volume missing/zero, or position notional would exceed 0.5% of it → refuse ({@link
 *       ExecutionDecision.RefusalReason#STALE_INPUT} / {@link
 *       ExecutionDecision.RefusalReason#DAILY_VOLUME_CAP_EXCEEDED}).
 *   <li>All checks passed → emit {@link ExecutionDecision.Execute}.
 * </ol>
 */
public final class RiskManager implements RiskManagerPort {

  /**
   * Wilder ATR lookback used for sizing and stop-loss distance. Public so callers computing it once
   * per bar (F11 — e.g. {@code BacktestHarness}) use the exact same period as the gate's internal
   * fallback, rather than a duplicated, driftable literal.
   */
  public static final int ATR_PERIOD = 14;

  private static final int DAILY_BARS = 24; // 24 hourly bars ≈ 1 trading day

  private final RiskParameters cfg;
  private final Clock clock;

  /**
   * @param clock source of "now" for the staleness check (CLAUDE.md §5: never {@code Instant.now()}
   *     inline). Live callers inject {@code Clock.systemUTC()}; backtests inject a clock bound to
   *     simulated bar time — see {@link BacktestClockSync}.
   */
  public RiskManager(RiskParameters cfg, Clock clock) {
    this.cfg = java.util.Objects.requireNonNull(cfg, "cfg");
    this.clock = java.util.Objects.requireNonNull(clock, "clock");
  }

  @Override
  public RiskParameters riskParameters() {
    return cfg;
  }

  @Override
  public ExecutionDecision gate(TechnicalSignal signal, PortfolioState state, MarketContext ctx) {
    String symbol = signal.symbol();
    Instant asOf = signal.asOf();

    // 1. HOLD signals need no execution.
    if (signal.direction() == Direction.HOLD) {
      return new ExecutionDecision.Refuse(
          symbol, ExecutionDecision.RefusalReason.HOLD_SIGNAL, "signal is HOLD", asOf);
    }

    // 2. Circuit breaker.
    if (state.circuitBreakerTripped()) {
      return new ExecutionDecision.Refuse(
          symbol,
          ExecutionDecision.RefusalReason.CIRCUIT_BREAKER_TRIPPED,
          "circuit breaker tripped: portfolio drawdown exceeded "
              + cfg.maxPortfolioDrawdownPct().toPlainString(),
          asOf);
    }

    // 2b. Daily loss limit (PLAN-012 Task F). Only fires when both the owner-configured limit AND
    // a day boundary to measure from are present -- mirrors the ctx.exchangeFilters() opt-in
    // pattern below (step 8b): absent means "not tracked here," a no-op, never a fail-safe refusal
    // (this is an extra live-phase guard, not a required risk input per CLAUDE.md §7).
    if (cfg.maxDailyLossAbs().isPresent() && state.dayStartEquity().isPresent()) {
      BigDecimal dailyLoss =
          state.dayStartEquity().get().subtract(state.equity(), IndicatorMath.MC);
      if (dailyLoss.compareTo(cfg.maxDailyLossAbs().get()) >= 0) {
        return new ExecutionDecision.Refuse(
            symbol,
            ExecutionDecision.RefusalReason.DAILY_LOSS_LIMIT_REACHED,
            String.format(
                "daily loss %s >= configured limit %s (day-start equity %s, current equity %s)",
                dailyLoss.toPlainString(),
                cfg.maxDailyLossAbs().get().toPlainString(),
                state.dayStartEquity().get().toPlainString(),
                state.equity().toPlainString()),
            asOf);
      }
    }

    // 3. Confidence floor.
    if (signal.confidence() < cfg.confidenceFloor()) {
      return new ExecutionDecision.Refuse(
          symbol,
          ExecutionDecision.RefusalReason.CONFIDENCE_BELOW_THRESHOLD,
          String.format("confidence %.3f < floor %.3f", signal.confidence(), cfg.confidenceFloor()),
          asOf);
    }

    // 4. Signal staleness: refuse if older than the configured tolerance.
    Duration age = Duration.between(asOf, clock.instant());
    if (age.compareTo(cfg.maxStaleInputAge()) > 0) {
      return new ExecutionDecision.Refuse(
          symbol,
          ExecutionDecision.RefusalReason.STALE_INPUT,
          "signal age " + age + " exceeds max " + cfg.maxStaleInputAge(),
          asOf);
    }

    // 5. SELL requires an existing long position to close (Phase-1 long-only exchange; no shorts).
    if (signal.direction() == Direction.SELL
        && state.openPositions().getOrDefault(symbol, BigDecimal.ZERO).signum() <= 0) {
      return new ExecutionDecision.Refuse(
          symbol,
          ExecutionDecision.RefusalReason.NO_OPEN_POSITION_TO_CLOSE,
          "SELL refused: no open position in " + symbol + " to close",
          asOf);
    }

    // 6. Need at least ATR_PERIOD + 1 candles.
    var candles = ctx.candles();
    if (candles.size() < ATR_PERIOD + 1) {
      return new ExecutionDecision.Refuse(
          symbol,
          ExecutionDecision.RefusalReason.STALE_INPUT,
          "need at least "
              + (ATR_PERIOD + 1)
              + " candles for ATR("
              + ATR_PERIOD
              + "); got "
              + candles.size(),
          asOf);
    }

    // 7. ATR: F11 — prefer the harness-supplied value (computed once per bar) over recomputing
    // O(N) from scratch on every gate call; fall back to internal computation for callers (e.g.
    // direct unit tests) that don't supply one.
    BigDecimal atr =
        ctx.lastAtr()
            .orElseGet(() -> AtrCalculator.calculate(candles, ATR_PERIOD).values().getLast());
    BigDecimal stopLossDistance =
        atr.multiply(BigDecimal.valueOf(cfg.stopLossAtrMult()), IndicatorMath.MC);
    if (stopLossDistance.signum() == 0) {
      return new ExecutionDecision.Refuse(
          symbol,
          ExecutionDecision.RefusalReason.STOP_LOSS_UNDERIVABLE,
          "ATR is zero — cannot derive stop-loss distance",
          asOf);
    }

    // Use the last candle's close as the entry price estimate.
    BigDecimal entryPrice = candles.getLast().close();
    BigDecimal equity = state.equity();

    // 8. Compute size: risk-per-trade / stopLossDistance (size in base-currency units).
    BigDecimal riskPerTrade = equity.multiply(cfg.maxPortfolioRiskPct(), IndicatorMath.MC);
    BigDecimal size = riskPerTrade.divide(stopLossDistance, IndicatorMath.MC);

    // Cap at maxPositionPct × equity / entryPrice.
    BigDecimal maxSizeByPct =
        equity
            .multiply(cfg.maxPositionPct(), IndicatorMath.MC)
            .divide(entryPrice, IndicatorMath.MC);
    if (size.compareTo(maxSizeByPct) > 0) {
      size = maxSizeByPct;
    }

    // Sanity: size must be positive.
    if (size.signum() <= 0) {
      return new ExecutionDecision.Refuse(
          symbol,
          ExecutionDecision.RefusalReason.STALE_INPUT,
          "computed position size is zero or negative",
          asOf);
    }

    // PLAN-010 Task D1 (F4): exchange trading filters, new entries only. A SELL in Phase 1 only
    // ever closes an existing long (step 5 above already required one to be open) — VirtualExchange
    // and the live order path close using the position's own already-filter-compliant size, never
    // this freshly computed one, so refusing a SELL here would risk trapping an operator in a
    // position too small to legally exit, directly contradicting CLAUDE.md §7's fail-safe
    // philosophy (never block an exit). {@code ctx.exchangeFilters()} is empty for every caller
    // that hasn't opted in (default), so this is a no-op unless a real value was supplied.
    if (signal.direction() == Direction.BUY && ctx.exchangeFilters().isPresent()) {
      ExchangeFilters filters = ctx.exchangeFilters().get();
      size = filters.roundQtyDown(size);
      BigDecimal roundedNotional = size.multiply(entryPrice, IndicatorMath.MC);
      if (size.signum() <= 0 || roundedNotional.compareTo(filters.minNotional()) < 0) {
        return new ExecutionDecision.Refuse(
            symbol,
            ExecutionDecision.RefusalReason.BELOW_EXCHANGE_MINIMUM,
            String.format(
                "size %s rounded down to qtyStepSize=%s -> notional %s < exchange minimum %s",
                size.toPlainString(),
                filters.qtyStepSize().toPlainString(),
                roundedNotional.toPlainString(),
                filters.minNotional().toPlainString()),
            asOf);
      }
    }

    BigDecimal positionNotional = size.multiply(entryPrice, IndicatorMath.MC);

    // 8c. Absolute per-position cap (PLAN-012 Task F). Strict '>' to match this and the sibling
    // percentage checks' own boundary convention (e.g. step 9's leverage check below) -- absent
    // when unconfigured, a no-op.
    if (cfg.maxPositionAbs().isPresent()
        && positionNotional.compareTo(cfg.maxPositionAbs().get()) > 0) {
      return new ExecutionDecision.Refuse(
          symbol,
          ExecutionDecision.RefusalReason.ABSOLUTE_EXPOSURE_CAP_EXCEEDED,
          String.format(
              "position notional %s > configured absolute cap %s",
              positionNotional.toPlainString(), cfg.maxPositionAbs().get().toPlainString()),
          asOf);
    }

    // 8d. Absolute total-deployed cap (PLAN-012 Task F) -- same shape as 8c, checked against
    // aggregate open notional including this trade rather than this trade alone.
    BigDecimal totalNotionalForAbsCheck =
        state.totalOpenNotional().add(positionNotional, IndicatorMath.MC);
    if (cfg.maxTotalDeployedAbs().isPresent()
        && totalNotionalForAbsCheck.compareTo(cfg.maxTotalDeployedAbs().get()) > 0) {
      return new ExecutionDecision.Refuse(
          symbol,
          ExecutionDecision.RefusalReason.ABSOLUTE_EXPOSURE_CAP_EXCEEDED,
          String.format(
              "total notional %s > configured absolute cap %s",
              totalNotionalForAbsCheck.toPlainString(),
              cfg.maxTotalDeployedAbs().get().toPlainString()),
          asOf);
    }

    // 9. Leverage cap.
    BigDecimal totalNotional = state.totalOpenNotional().add(positionNotional, IndicatorMath.MC);
    BigDecimal maxNotional = equity.multiply(cfg.maxLeverage(), IndicatorMath.MC);
    if (totalNotional.compareTo(maxNotional) > 0) {
      return new ExecutionDecision.Refuse(
          symbol,
          ExecutionDecision.RefusalReason.LEVERAGE_CAP_EXCEEDED,
          String.format(
              "total notional %s would exceed %s × equity %s",
              totalNotional.toPlainString(),
              cfg.maxLeverage().toPlainString(),
              equity.toPlainString()),
          asOf);
    }

    // 10. Daily volume cap: position notional < maxDailyVolumePct × daily volume. F4: zero or
    // unavailable volume now fails safe (refuse) instead of silently skipping the check.
    BigDecimal dailyVolume = computeDailyVolume(candles);
    if (dailyVolume.signum() <= 0) {
      return new ExecutionDecision.Refuse(
          symbol,
          ExecutionDecision.RefusalReason.STALE_INPUT,
          "daily volume unavailable — failing safe per CLAUDE.md §7",
          asOf);
    }
    BigDecimal maxByVolume =
        dailyVolume
            .multiply(entryPrice, IndicatorMath.MC)
            .multiply(cfg.maxDailyVolumePct(), IndicatorMath.MC);
    if (positionNotional.compareTo(maxByVolume) > 0) {
      return new ExecutionDecision.Refuse(
          symbol,
          ExecutionDecision.RefusalReason.DAILY_VOLUME_CAP_EXCEEDED,
          String.format(
              "position notional %s > %.1f%% of daily volume (%s)",
              positionNotional.toPlainString(),
              cfg.maxDailyVolumePct().doubleValue() * 100,
              maxByVolume.toPlainString()),
          asOf);
    }

    // 11. Compute stop-loss price (advisory; VirtualExchange uses stopLossDistance from fill).
    // @see ADR-0015 (PLAN-012 Task E) -- this price stop is computed and returned uniformly for
    // every Execute, but it is only economically meaningful for a directional (OHLCV) position. A
    // FUNDING_AWARE consumer's position is price-neutral by construction, so a caller must treat
    // stopLoss()/stopLossDistance() as advisory, not authoritative, for that StrategyKind -- the
    // real risk controls for a delta-neutral position are a margin/liquidation guard on the perp
    // leg plus a basis-divergence limit (ADR-0015), not a price stop on either leg.
    BigDecimal stopLossPrice =
        signal.direction() == Direction.BUY
            ? entryPrice.subtract(stopLossDistance, IndicatorMath.MC)
            : entryPrice.add(stopLossDistance, IndicatorMath.MC);

    // 12. Take-profit at 2:1 risk-reward (2 × stopLossDistance).
    BigDecimal tpDistance = stopLossDistance.multiply(BigDecimal.valueOf(2), IndicatorMath.MC);

    // PLAN-010 Task D1: tick-round both managed prices toward the entry when exchange filters are
    // known — see ExchangeFilters' Javadoc for the full per-side matrix. Unlike the sizing check
    // above, this runs for both directions and never refuses: it only makes an already-computed
    // price exchange-legal, so there is no "never block exits" concern. stopLossDistance/tpDistance
    // are re-derived from the rounded prices (rather than rounded independently) so Execute's
    // absolute-price and distance fields stay mutually consistent — the live order path
    // (NativeStopManager) sends stopLoss() itself, while VirtualExchange (backtest parity, D3)
    // uses stopLossDistance; both must agree on exactly the same stop level.
    if (ctx.exchangeFilters().isPresent()) {
      ExchangeFilters filters = ctx.exchangeFilters().get();
      stopLossPrice = filters.roundStopLossTowardEntry(stopLossPrice, signal.direction());
      stopLossDistance = stopLossPrice.subtract(entryPrice, IndicatorMath.MC).abs();

      BigDecimal tpPrice =
          signal.direction() == Direction.BUY
              ? entryPrice.add(tpDistance, IndicatorMath.MC)
              : entryPrice.subtract(tpDistance, IndicatorMath.MC);
      BigDecimal roundedTpPrice = filters.roundTakeProfitTowardEntry(tpPrice, signal.direction());
      tpDistance = roundedTpPrice.subtract(entryPrice, IndicatorMath.MC).abs();
    }

    String explanation =
        String.format(
            "RM approved %s %s: size=%.6f, SL-dist=%s, TP-dist=%s (ATR=%.4f × %.1f), conf=%.3f",
            signal.direction(),
            symbol,
            size.doubleValue(),
            stopLossDistance.toPlainString(),
            tpDistance.toPlainString(),
            atr.doubleValue(),
            cfg.stopLossAtrMult(),
            signal.confidence());

    List<Factor> factors =
        List.of(
            new Factor("RM_ATR", "ATR(" + ATR_PERIOD + ")=" + atr.toPlainString(), 1.0),
            new Factor(
                "RM_SIZE",
                "size=" + size.toPlainString() + " (risk=" + riskPerTrade.toPlainString() + ")",
                1.0));

    return new ExecutionDecision.Execute(
        symbol,
        signal.direction(),
        size,
        positionNotional,
        stopLossPrice,
        stopLossDistance,
        Optional.of(tpDistance),
        explanation,
        factors,
        asOf);
  }

  /** Sums the volume of the last {@link #DAILY_BARS} candles as a daily-volume proxy. */
  private static BigDecimal computeDailyVolume(
      java.util.List<app.viglide.core.domain.Candle> candles) {
    int start = Math.max(0, candles.size() - DAILY_BARS);
    BigDecimal vol = BigDecimal.ZERO;
    for (int i = start; i < candles.size(); i++) {
      vol = vol.add(candles.get(i).volume(), IndicatorMath.MC);
    }
    return vol;
  }
}
