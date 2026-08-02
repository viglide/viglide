package app.viglide.core.domain;

import app.viglide.core.indicator.IndicatorMath;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Binance-style per-symbol exchange trading filters (PLAN-010 Task D, finding F4): the minimum
 * order notional, the quantity step size ({@code LOT_SIZE}), and the price tick size ({@code
 * PRICE_FILTER}). Pure data, injected — {@code viglide-core} never fetches these itself. {@code
 * viglide-runtime}'s {@code BinanceRestClient} fetches them ({@code exchangeInfo}) for the
 * live/paper path; {@link app.viglide.core.backtest.BacktestConfig} carries an optional value
 * through for backtest parity. Both reach {@link app.viglide.core.risk.RiskManager} via {@link
 * MarketContext#exchangeFilters()}.
 *
 * <p><strong>The rule every method here obeys (PLAN-010 D10-2's "refuse, don't bump"):</strong>
 * rounding may only ever move a value toward being smaller/safer, never toward looking better than
 * the unrounded value would have:
 *
 * <table>
 * <caption>Per-side rounding matrix</caption>
 * <tr><th></th><th>{@link #roundStopLossTowardEntry}</th><th>{@link #roundTakeProfitTowardEntry}</th></tr>
 * <tr><td>{@code BUY} (long)</td><td>rounds UP — stop sits below entry, so up is <em>toward</em>
 *     entry: a tighter stop that triggers on a smaller adverse move</td>
 *     <td>rounds DOWN — target sits above entry, so down is toward entry: less booked profit</td></tr>
 * <tr><td>{@code SELL} (short)</td><td>rounds DOWN — stop sits above entry, so down is toward
 *     entry: same tightening effect, mirrored</td>
 *     <td>rounds UP — target sits below entry, so up is toward entry: less booked profit</td></tr>
 * </table>
 *
 * <p>Both rows reduce to one invariant: every managed price rounds toward the entry price, never
 * away from it. {@link #roundQtyDown} always floors regardless of side — a smaller order is always
 * the safe direction.
 */
public record ExchangeFilters(
    BigDecimal minNotional, BigDecimal qtyStepSize, BigDecimal priceTickSize) {

  /**
   * Validates that the filter values themselves are sane (a zero step/tick size is meaningless).
   */
  public ExchangeFilters {
    Objects.requireNonNull(minNotional, "minNotional");
    Objects.requireNonNull(qtyStepSize, "qtyStepSize");
    Objects.requireNonNull(priceTickSize, "priceTickSize");
    if (minNotional.signum() < 0) {
      throw new IllegalArgumentException("minNotional must be >= 0, got: " + minNotional);
    }
    if (qtyStepSize.signum() <= 0) {
      throw new IllegalArgumentException("qtyStepSize must be > 0, got: " + qtyStepSize);
    }
    if (priceTickSize.signum() <= 0) {
      throw new IllegalArgumentException("priceTickSize must be > 0, got: " + priceTickSize);
    }
  }

  /**
   * Rounds a quantity DOWN to the nearest multiple of {@link #qtyStepSize()} — never up, per D10-2.
   * May round to zero for a quantity smaller than one step; callers must check for that.
   */
  public BigDecimal roundQtyDown(BigDecimal qty) {
    Objects.requireNonNull(qty, "qty");
    return floorToStep(qty, qtyStepSize);
  }

  /**
   * Rounds a stop-loss price to the nearest {@link #priceTickSize()}, toward the entry price — see
   * this class's Javadoc for the full matrix. {@code side} is the position's side ({@code BUY} =
   * long, {@code SELL} = short).
   */
  public BigDecimal roundStopLossTowardEntry(BigDecimal price, Direction side) {
    Objects.requireNonNull(price, "price");
    Objects.requireNonNull(side, "side");
    return side == Direction.BUY
        ? ceilToStep(price, priceTickSize)
        : floorToStep(price, priceTickSize);
  }

  /**
   * Rounds a take-profit price to the nearest {@link #priceTickSize()}, toward the entry price —
   * see this class's Javadoc for the full matrix. {@code side} is the position's side ({@code BUY}
   * = long, {@code SELL} = short).
   */
  public BigDecimal roundTakeProfitTowardEntry(BigDecimal price, Direction side) {
    Objects.requireNonNull(price, "price");
    Objects.requireNonNull(side, "side");
    return side == Direction.BUY
        ? floorToStep(price, priceTickSize)
        : ceilToStep(price, priceTickSize);
  }

  private static BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
    return value.divideToIntegralValue(step, IndicatorMath.MC).multiply(step, IndicatorMath.MC);
  }

  private static BigDecimal ceilToStep(BigDecimal value, BigDecimal step) {
    BigDecimal floor = floorToStep(value, step);
    return floor.compareTo(value) == 0 ? floor : floor.add(step, IndicatorMath.MC);
  }
}
