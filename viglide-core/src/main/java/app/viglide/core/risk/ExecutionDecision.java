package app.viglide.core.risk;

import app.viglide.core.domain.Direction;
import app.viglide.core.domain.Factor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Layer-4 Risk Manager output (PLAN-005b, ADR-0006, CLAUDE.md §4). Every strategy signal must pass
 * through the Risk Manager before execution; the result is always one of these two sealed cases —
 * callers must handle both at compile time.
 *
 * <p>{@link Execute} carries the RM-approved size and mandatory stop-loss. {@link Refuse} carries
 * the reason code so the trade-log audit trail can explain every non-trade.
 */
public sealed interface ExecutionDecision
    permits ExecutionDecision.Execute, ExecutionDecision.Refuse {

  String symbol();

  Instant asOf();

  /**
   * The Risk Manager approved the signal. Size and stop-loss are derived from portfolio volatility
   * (ATR(14)) and the hard limits in CLAUDE.md §7.
   *
   * <p>{@code stopLossDistance} is the distance from the entry price to the stop price (positive,
   * in price units). VirtualExchange applies it relative to the actual fill price so there is no
   * one-bar timing mismatch from evaluation-close to fill-open. {@code stopLoss} is the advisory
   * absolute price level computed from the evaluation-close estimate; it is carried for audit
   * logging but VirtualExchange prefers {@code stopLossDistance}.
   *
   * <p>{@code takeProfit} is a distance from the entry price (positive). VirtualExchange applies it
   * on the opposite side of the entry from the stop.
   */
  record Execute(
      String symbol,
      Direction side,
      BigDecimal size,
      BigDecimal notional,
      BigDecimal stopLoss,
      BigDecimal stopLossDistance,
      Optional<BigDecimal> takeProfit,
      String explanation,
      List<Factor> factors,
      Instant asOf)
      implements ExecutionDecision {

    /**
     * @param notional {@code size × entryPrice} at the moment the Risk Manager sized this position
     *     (PLAN-012 Task D, review finding F11) — surfaced explicitly so callers never have to
     *     re-derive it against a price of their own choosing, which is only safe by accident when a
     *     caller's own price series happens to match the one the RM sized against. {@code size}
     *     stays in base-currency units (order placement needs base units); {@code notional} is in
     *     quote currency.
     */
    public Execute {
      Objects.requireNonNull(symbol, "symbol");
      Objects.requireNonNull(side, "side");
      Objects.requireNonNull(size, "size");
      Objects.requireNonNull(notional, "notional");
      Objects.requireNonNull(stopLoss, "stopLossDistance");
      Objects.requireNonNull(stopLossDistance, "stopLossDistance");
      Objects.requireNonNull(takeProfit, "takeProfit");
      Objects.requireNonNull(explanation, "explanation");
      Objects.requireNonNull(factors, "factors");
      Objects.requireNonNull(asOf, "asOf");
      if (side == Direction.HOLD) {
        throw new IllegalArgumentException("Execute.side cannot be HOLD");
      }
      if (size.signum() <= 0) {
        throw new IllegalArgumentException("Execute.size must be > 0, got: " + size);
      }
      if (notional.signum() <= 0) {
        throw new IllegalArgumentException("Execute.notional must be > 0, got: " + notional);
      }
      if (stopLoss.signum() <= 0) {
        throw new IllegalArgumentException("Execute.stopLoss must be > 0, got: " + stopLoss);
      }
      if (stopLossDistance.signum() <= 0) {
        throw new IllegalArgumentException(
            "Execute.stopLossDistance must be > 0, got: " + stopLossDistance);
      }
      factors = List.copyOf(factors);
    }
  }

  /**
   * The Risk Manager refused the signal. The {@link RefusalReason} pinpoints which hard limit was
   * breached so the audit trail is unambiguous.
   */
  record Refuse(String symbol, RefusalReason reason, String explanation, Instant asOf)
      implements ExecutionDecision {

    public Refuse {
      Objects.requireNonNull(symbol, "symbol");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(explanation, "explanation");
      Objects.requireNonNull(asOf, "asOf");
    }
  }

  /**
   * Hard-limit reason codes for refusals. Each maps to a specific CLAUDE.md §7 constraint.
   *
   * <p>Note: position-size capping (2% of equity) is a silent cap, not a refusal — the RM sizes
   * down to the limit rather than refusing the trade, so there is no {@code
   * POSITION_SIZE_CAP_EXCEEDED} case here (ADR-0006 amendment, F8).
   */
  enum RefusalReason {
    /** Portfolio max drawdown of 15% exceeded — circuit breaker tripped. */
    CIRCUIT_BREAKER_TRIPPED,
    /** Position notional would exceed 0.5% of the pair's daily volume. */
    DAILY_VOLUME_CAP_EXCEEDED,
    /** ATR is zero or insufficient candles to derive a stop-loss. */
    STOP_LOSS_UNDERIVABLE,
    /** Position notional would exceed equity × max-leverage (2×). */
    LEVERAGE_CAP_EXCEEDED,
    /** A required risk input (ATR, volume, or signal recency) was missing or stale. */
    STALE_INPUT,
    /** Signal confidence is below the configured floor. */
    CONFIDENCE_BELOW_THRESHOLD,
    /** Signal direction is HOLD — no execution needed. */
    HOLD_SIGNAL,
    /** SELL signal received while flat — there is no open long position to close. */
    NO_OPEN_POSITION_TO_CLOSE,
    /**
     * PLAN-009 Task I1: the exchange-native stop-loss order for a just-filled entry could not be
     * placed (rejected, timed out, or the exchange call itself failed). The position was
     * immediately flattened rather than left resting without a stop — §7's "stop-loss mandatory"
     * applies between bars, not just at decision time. Also used for the catastrophic case where
     * the flatten attempt itself also failed — see the explanation text for which.
     */
    STOP_PLACEMENT_FAILED,
    /**
     * PLAN-009 Task I1: the real entry order (testnet/live modes) was rejected, unreachable, or
     * accepted but not reported {@code FILLED} — no position was opened, so no stop was attempted
     * either.
     */
    ENTRY_ORDER_FAILED,
    /**
     * PLAN-010 Task D1 (finding F4): after rounding the computed quantity down to the exchange's
     * {@code qtyStepSize}, the resulting position notional is below the exchange's {@code
     * minNotional} for this symbol — the order would be rejected by the exchange as too small.
     * Entries only (D2's fail-safe never blocks an exit); refuse, never round up past what §7's
     * caps allow ({@code ExchangeFilters}' "refuse, don't bump" rule).
     */
    BELOW_EXCHANGE_MINIMUM,
    /**
     * PLAN-011 Task B (finding F2): the exchange's reported position for this symbol disagreed with
     * this bot's own local bookkeeping — e.g. the exchange showed an open position local records
     * didn't know about (or vice versa) beyond what the routine "stop fired, reconcile to flat"
     * case resolves on its own. New entries are refused until a subsequent reconciliation pass
     * confirms both sides agree again (§7: an untrustworthy risk input means fail-safe HOLD); an
     * exit is never blocked.
     */
    POSITION_DIVERGENCE,
    /**
     * PLAN-012 Task F: position notional (this trade, or the portfolio's total open notional
     * including it) would exceed an owner-configured absolute-dollar cap ({@link
     * RiskParameters#maxPositionAbs()} / {@link RiskParameters#maxTotalDeployedAbs()}) — a static,
     * legible ceiling that binds independently of, and in addition to, the percentage-of-equity
     * caps (§7). Entries only, same as every other exposure-cap reason in this enum; absent when
     * both limits are unconfigured ({@link Optional#empty()}), so existing callers are unaffected.
     */
    ABSOLUTE_EXPOSURE_CAP_EXCEEDED,
    /**
     * PLAN-012 Task F: realised-plus-unrealised loss since the start of the current UTC day has
     * reached the owner-configured {@link RiskParameters#maxDailyLossAbs()} — halts new entries for
     * the remainder of the UTC day (self-clears at the next day boundary, unlike {@link
     * #CIRCUIT_BREAKER_TRIPPED}, which requires an operator restart). Absent when unconfigured.
     */
    DAILY_LOSS_LIMIT_REACHED
  }
}
