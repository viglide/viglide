package app.viglide.core.backtest;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.indicator.IndicatorMath;
import app.viglide.core.risk.ExecutionDecision;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-symbol long-only virtual exchange. The exchange holds at most one open position at a time
 * (Phase-1 scope — no shorts beyond a {@code SELL}-as-exit, no pyramiding) and processes events in
 * this order on every new candle:
 *
 * <ol>
 *   <li>Fill any pending market order at the candle's open (no look-ahead).
 *   <li>If a position is open, evaluate stop-loss and take-profit against the candle's low/high
 *       (or, when a finer-grained sub-bar series is supplied — PLAN-009 Task B2 — walk the sub-bars
 *       in order for a more accurate ordering; see {@link #onCandle(Candle, List)}). Conservative
 *       worst-case fill: a stop fills at the stop price (not the wick low).
 *   <li>Mark-to-market the equity at the candle's close and append to the equity curve.
 * </ol>
 *
 * <p>A {@code BUY} signal from the strategy queues a pending open order; a {@code SELL} signal
 * queues a pending close (only if a position is open). Both fills incur taker fees plus slippage
 * per {@link FeeModel}.
 *
 * <p>Deterministic and side-effect free: identical (candles, signals) ⇒ identical equity curve and
 * trade list. No randomness, no wall-clock, no I/O.
 */
final class VirtualExchange {

  private final String symbol;
  private final BacktestConfig cfg;
  private final BigDecimal adverseFactor; // taker + slippage as a fraction
  // PLAN-011 Task E: the decision bar's own duration, needed only to validate that a supplied
  // sub-bar series actually tiles the parent bar's time window (see
  // validateSubBarsWithinParentRange) -- unrelated to any price/fee arithmetic above.
  private final Duration decisionBarDuration;

  private BigDecimal cash;
  // Position state. Either all four are null (flat) or all four are set (long).
  private BigDecimal positionSize;
  private BigDecimal positionEntryPrice;
  private Instant positionEntryTime;
  private BigDecimal positionStopLoss;
  private BigDecimal positionTakeProfit;

  // Pending order to be filled at NEXT candle's open. Null if no pending order.
  private Direction pendingOrder;
  // When non-null, the RM-approved decision overrides cfg sizing for the next pending order.
  private ExecutionDecision.Execute pendingRmDecision;

  private final List<Trade> trades = new ArrayList<>();
  private final List<EquityPoint> equityCurve = new ArrayList<>();
  // PLAN-009 Task C: fee+slippage cost paid on every fill, tracked separately from the net P&L
  // baked into Trade prices, so "fee share of gross P&L" can be reported per interval (the
  // mechanism the interval study exists to make visible — see CLAUDE.md §0.2's fee arithmetic).
  private BigDecimal totalFeesPaid = BigDecimal.ZERO;

  VirtualExchange(String symbol, BacktestConfig cfg, CandleInterval interval) {
    this.symbol = symbol;
    this.cfg = cfg;
    this.cash = cfg.startingCash();
    this.adverseFactor = cfg.fees().totalAdverseFactor(IndicatorMath.MC);
    this.decisionBarDuration = interval.duration();
  }

  /**
   * Processes one new candle with no sub-bar data — equivalent to {@link #onCandle(Candle, List)}
   * with an empty sub-bar list (the original, worst-case-pessimistic SL/TP resolution).
   */
  void onCandle(Candle candle) {
    onCandle(candle, List.of());
  }

  /**
   * Processes one new candle. Must be called in chronological order. The pending order from the
   * previous bar is filled at {@code candle.open()}, then SL/TP are evaluated, then equity is
   * recorded.
   *
   * <p>PLAN-009 Task B2: {@code subBars} is an optional, finer-grained candle series covering this
   * decision bar's time window, already sliced by the caller (no I/O or lookup happens here — see
   * {@link BacktestHarness}, which owns the slicing). When empty, SL/TP resolution falls back to
   * the original rule: if both could trigger inside the bar we cannot tell which came first, so
   * assume the worse (stop-loss) hit. When non-empty, each sub-bar is validated to lie within the
   * parent candle's price range (a sub-bar outside the parent's [low, high] indicates a data or
   * aggregation bug — fail loudly, CLAUDE.md §5) and then walked in order, triggering whichever of
   * SL/TP is crossed first; the worst-case tie-break applies only within a single sub-bar where
   * both cross simultaneously, since ordering within one bar is still genuinely ambiguous.
   */
  void onCandle(Candle candle, List<Candle> subBars) {
    // 1. Fill pending order at this candle's open.
    if (pendingOrder == Direction.BUY && positionSize == null) {
      openLong(candle);
    } else if (pendingOrder == Direction.SELL && positionSize != null) {
      closeLong(candle, candle.open(), ExitReason.SIGNAL, candle.openTime());
    }
    pendingOrder = null;

    // 2. Evaluate SL/TP.
    if (positionSize != null) {
      if (subBars.isEmpty()) {
        boolean slHit = positionStopLoss != null && candle.low().compareTo(positionStopLoss) <= 0;
        boolean tpHit =
            positionTakeProfit != null && candle.high().compareTo(positionTakeProfit) >= 0;
        if (slHit) {
          closeLong(candle, positionStopLoss, ExitReason.STOP_LOSS, candle.openTime());
        } else if (tpHit) {
          closeLong(candle, positionTakeProfit, ExitReason.TAKE_PROFIT, candle.openTime());
        }
      } else {
        validateSubBarsWithinParentRange(candle, subBars);
        for (Candle sub : subBars) {
          if (positionSize == null) break; // already closed by an earlier sub-bar this decision bar
          boolean slHit = positionStopLoss != null && sub.low().compareTo(positionStopLoss) <= 0;
          boolean tpHit =
              positionTakeProfit != null && sub.high().compareTo(positionTakeProfit) >= 0;
          if (slHit) {
            // Ambiguous-within-one-sub-bar still defaults to the worse outcome (stop-loss), same
            // convention as the coarse path, just applied at sub-bar granularity instead of the
            // whole decision bar.
            closeLong(candle, positionStopLoss, ExitReason.STOP_LOSS, candle.openTime());
          } else if (tpHit) {
            closeLong(candle, positionTakeProfit, ExitReason.TAKE_PROFIT, candle.openTime());
          }
        }
      }
    }

    // 3. Mark-to-market at this candle's close.
    equityCurve.add(new EquityPoint(candle.openTime(), markToMarket(candle.close())));
  }

  /**
   * Data-integrity guard (PLAN-009 Task B2, extended PLAN-011 Task E / F5): every sub-bar must lie
   * within its parent decision bar's price range <em>and</em> time window, and the series must be
   * in chronological order — sub-bars are supposed to tile the parent by construction, and a
   * violation of any of these means the sub-bar series and the decision-bar series were not derived
   * from the same source (e.g. mismatched aggregation, wrong pair/period joined, a mis-sliced
   * cursor) — fail loudly rather than silently trust corrupted intra-bar data (CLAUDE.md §5).
   * {@link Candle} has no explicit close-time field, so "tiles the window" is checked as: every
   * sub-bar's {@code openTime} falls in {@code [parent.openTime(), parent.openTime() +
   * decisionBarDuration)} — the same half-open interval the callers that slice these series ({@code
   * BacktestHarness}, {@code FundingArbHarnessV2}) already use as their own slicing bound.
   */
  private void validateSubBarsWithinParentRange(Candle parent, List<Candle> subBars) {
    Instant windowEnd = parent.openTime().plus(decisionBarDuration);
    Instant previousOpenTime = null;
    for (Candle sub : subBars) {
      if (sub.high().compareTo(parent.high()) > 0 || sub.low().compareTo(parent.low()) < 0) {
        throw new IllegalStateException(
            "sub-bar out of parent bar's range at "
                + sub.openTime()
                + ": parent=["
                + parent.low()
                + ","
                + parent.high()
                + "] sub=["
                + sub.low()
                + ","
                + sub.high()
                + "] (parent bar opened "
                + parent.openTime()
                + ")");
      }
      if (sub.openTime().isBefore(parent.openTime()) || !sub.openTime().isBefore(windowEnd)) {
        throw new IllegalStateException(
            "sub-bar does not lie within its parent bar's time window at "
                + sub.openTime()
                + ": parent window=["
                + parent.openTime()
                + ","
                + windowEnd
                + ")");
      }
      if (previousOpenTime != null && !sub.openTime().isAfter(previousOpenTime)) {
        throw new IllegalStateException(
            "sub-bars are not strictly ascending by openTime: "
                + previousOpenTime
                + " then "
                + sub.openTime());
      }
      previousOpenTime = sub.openTime();
    }
  }

  /**
   * Records a strategy signal as a pending order, to be filled at the NEXT candle's open.
   *
   * <p>F2 (PLAN-007 Task D): this is the ungated legacy path, reachable only from {@link
   * BacktestHarness}'s own RM-disabled overloads for offline research backtests — never from a
   * live/real order-placing path. Package-private by design; {@code OrderPathGuardTest} fails the
   * build if any production class outside {@code app.viglide.core.backtest} ever gains a dependency
   * on {@link VirtualExchange} (CLAUDE.md §11 — no order path may bypass the Risk Manager).
   */
  @Deprecated(forRemoval = false)
  void onSignal(TechnicalSignal signal) {
    Direction d = signal.direction();
    if (d == Direction.HOLD) return;
    if (d == Direction.BUY && positionSize == null) pendingOrder = Direction.BUY;
    if (d == Direction.SELL && positionSize != null) pendingOrder = Direction.SELL;
  }

  /**
   * Records an RM-approved execution decision as a pending order. The RM's size, stop-loss, and
   * optional take-profit override the {@link BacktestConfig} defaults.
   *
   * <p>F5: a SELL decision reaching the exchange while flat is a Risk Manager bug, not a no-op —
   * the RM must refuse {@code NO_OPEN_POSITION_TO_CLOSE} upstream instead of ever handing this
   * exchange a SELL to close nothing. Fail loudly (CLAUDE.md §5) rather than silently drop it.
   */
  void onExecuteDecision(ExecutionDecision.Execute decision) {
    if (decision.side() == Direction.BUY && positionSize == null) {
      pendingOrder = Direction.BUY;
      pendingRmDecision = decision;
    } else if (decision.side() == Direction.SELL) {
      if (positionSize == null) {
        throw new IllegalStateException(
            "SELL decision reached exchange while flat — RM must refuse upstream");
      }
      pendingOrder = Direction.SELL;
      pendingRmDecision = decision; // close uses existing position size; RM decision recorded
    }
  }

  /** {@code true} when a long position is currently open. */
  boolean hasOpenPosition() {
    return positionSize != null;
  }

  /**
   * Current position size in base-currency units, or {@link BigDecimal#ZERO} when flat. Multiply by
   * a mark price to get notional exposure in quote currency (F3/F12).
   */
  BigDecimal positionSize() {
    return positionSize == null ? BigDecimal.ZERO : positionSize;
  }

  /**
   * Returns the current mark-to-market equity using the given market price. Does not modify
   * exchange state.
   */
  BigDecimal markToMarketNow(BigDecimal markPrice) {
    return markToMarket(markPrice);
  }

  /** Closes any open position at the last candle's close. Idempotent if already flat. */
  void closeAll(Candle lastCandle) {
    if (positionSize != null) {
      closeLong(lastCandle, lastCandle.close(), ExitReason.END_OF_DATA, lastCandle.openTime());
    }
  }

  List<Trade> trades() {
    return List.copyOf(trades);
  }

  List<EquityPoint> equityCurve() {
    return List.copyOf(equityCurve);
  }

  BigDecimal cash() {
    return cash;
  }

  /** Total fee+slippage cost paid across every fill so far (PLAN-009 Task C). */
  BigDecimal totalFeesPaid() {
    return totalFeesPaid;
  }

  // ── internals ────────────────────────────────────────────────────────────────────────────────

  private void openLong(Candle candle) {
    BigDecimal fillPrice =
        candle.open().multiply(BigDecimal.ONE.add(adverseFactor), IndicatorMath.MC);

    BigDecimal size;
    BigDecimal sl;
    BigDecimal tp;

    if (pendingRmDecision != null) {
      // RM-gated path: use RM-supplied size; compute SL/TP relative to actual fill price so
      // the one-bar lag between evaluation-close and fill-open doesn't shift the stop.
      size = pendingRmDecision.size();
      BigDecimal slDist = pendingRmDecision.stopLossDistance();
      sl =
          pendingRmDecision.side() == Direction.BUY
              ? fillPrice.subtract(slDist, IndicatorMath.MC)
              : fillPrice.add(slDist, IndicatorMath.MC);
      tp =
          pendingRmDecision
              .takeProfit()
              .map(
                  tpDist ->
                      pendingRmDecision.side() == Direction.BUY
                          ? fillPrice.add(tpDist, IndicatorMath.MC)
                          : fillPrice.subtract(tpDist, IndicatorMath.MC))
              .orElse(null);
      pendingRmDecision = null;
    } else {
      // Legacy path: size from config; stop-loss/TP as pct of fill price.
      BigDecimal notional = cash.multiply(cfg.maxPositionPct(), IndicatorMath.MC);
      size = notional.divide(fillPrice, IndicatorMath.MC);
      sl =
          cfg.stopLossPct() == null
              ? null
              : fillPrice.multiply(BigDecimal.ONE.subtract(cfg.stopLossPct()), IndicatorMath.MC);
      tp =
          cfg.takeProfitPct() == null
              ? null
              : fillPrice.multiply(BigDecimal.ONE.add(cfg.takeProfitPct()), IndicatorMath.MC);
    }

    if (size.signum() <= 0) return; // not enough cash to open

    totalFeesPaid =
        totalFeesPaid.add(size.multiply(fillPrice.subtract(candle.open()), IndicatorMath.MC).abs());
    cash = cash.subtract(size.multiply(fillPrice, IndicatorMath.MC));
    positionSize = size;
    positionEntryPrice = fillPrice;
    positionEntryTime = candle.openTime();
    positionStopLoss = sl;
    positionTakeProfit = tp;
  }

  private void closeLong(Candle candle, BigDecimal exitRefPrice, ExitReason reason, Instant at) {
    BigDecimal fillPrice =
        exitRefPrice.multiply(BigDecimal.ONE.subtract(adverseFactor), IndicatorMath.MC);
    BigDecimal proceeds = positionSize.multiply(fillPrice, IndicatorMath.MC);
    BigDecimal cost = positionSize.multiply(positionEntryPrice, IndicatorMath.MC);
    BigDecimal pnl = proceeds.subtract(cost);
    cash = cash.add(proceeds);
    totalFeesPaid =
        totalFeesPaid.add(
            positionSize.multiply(exitRefPrice.subtract(fillPrice), IndicatorMath.MC).abs());

    trades.add(
        new Trade(
            positionEntryTime,
            at,
            Direction.BUY,
            positionEntryPrice,
            fillPrice,
            positionSize,
            pnl,
            reason));

    positionSize = null;
    positionEntryPrice = null;
    positionEntryTime = null;
    positionStopLoss = null;
    positionTakeProfit = null;
  }

  private BigDecimal markToMarket(BigDecimal markPrice) {
    if (positionSize == null) return cash;
    return cash.add(positionSize.multiply(markPrice, IndicatorMath.MC));
  }
}
