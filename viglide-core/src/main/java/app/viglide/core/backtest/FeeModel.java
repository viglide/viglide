package app.viglide.core.backtest;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/**
 * Trading-cost assumptions for a backtest. Fees and slippage are expressed in basis points (bps); 1
 * bps = 0.01%. {@link #binanceDefault()} mirrors the assumptions in {@code
 * docs/tasks/backtesting_harness.md}.
 *
 * <p>Only {@code takerBps} and {@code slippageBps} feed {@link #totalAdverseFactor}, i.e. every
 * fill in this backtester is costed as a taker (market) order; {@code makerBps} is carried for
 * display/audit purposes and by {@link #maker()} (PLAN-008 Task E), which routes the maker rate
 * through the taker-costed field so the harness math picks it up without a separate code path.
 */
public record FeeModel(BigDecimal makerBps, BigDecimal takerBps, BigDecimal slippageBps) {

  /** Taker (market order) fee: 5 bps = 0.05% = 0.0005 as a fraction. */
  public static final BigDecimal TAKER_FEE_BPS = bps("5");

  /** Taker (market order) slippage: 5 bps = 0.05% = 0.0005 as a fraction. */
  public static final BigDecimal TAKER_SLIPPAGE_BPS = bps("5");

  /**
   * Maker (resting limit order) fee: 2 bps = 0.02% = 0.0002 as a fraction.
   *
   * <p><strong>Optimistic bound</strong> — assumes resting limit orders always fill at touch with
   * no adverse selection; real maker fills are worse. Use as the best-case edge of a fee
   * sensitivity band, never as the base case.
   */
  public static final BigDecimal MAKER_FEE_BPS = bps("2");

  /** Maker (resting limit order) slippage: zero — see {@link #MAKER_FEE_BPS}'s optimism caveat. */
  public static final BigDecimal MAKER_SLIPPAGE_BPS = BigDecimal.ZERO;

  /** All three bps values must be non-negative. */
  public FeeModel {
    Objects.requireNonNull(makerBps, "makerBps");
    Objects.requireNonNull(takerBps, "takerBps");
    Objects.requireNonNull(slippageBps, "slippageBps");
    if (makerBps.signum() < 0) throw new IllegalArgumentException("makerBps must be >= 0");
    if (takerBps.signum() < 0) throw new IllegalArgumentException("takerBps must be >= 0");
    if (slippageBps.signum() < 0) throw new IllegalArgumentException("slippageBps must be >= 0");
  }

  /** Binance default: 0.02% maker (display-only), 0.05% taker, 0.05% market-order slippage. */
  public static FeeModel binanceDefault() {
    return taker();
  }

  /** Taker (market order) fee model — the base-case cost assumption. */
  public static FeeModel taker() {
    return new FeeModel(MAKER_FEE_BPS, TAKER_FEE_BPS, TAKER_SLIPPAGE_BPS);
  }

  /**
   * Maker (resting limit order) fee model (PLAN-008 Task E) — see {@link #MAKER_FEE_BPS}'s optimism
   * caveat. Use as the best-case edge of a fee sensitivity band, never as the base case.
   */
  public static FeeModel maker() {
    return new FeeModel(MAKER_FEE_BPS, MAKER_FEE_BPS, MAKER_SLIPPAGE_BPS);
  }

  /** Zero costs — useful for sanity tests that need to read raw PnL without friction. */
  public static FeeModel zero() {
    return new FeeModel(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
  }

  /**
   * Scales every bps component by {@code scale} (PLAN-008 Task E's {@code --fee-scale}). {@code
   * scale=0} disables trading costs entirely; {@code scale=2} doubles them for a pessimistic
   * sensitivity check.
   */
  public FeeModel scaled(BigDecimal scale, MathContext mc) {
    Objects.requireNonNull(scale, "scale");
    if (scale.signum() < 0) throw new IllegalArgumentException("scale must be >= 0, got: " + scale);
    return new FeeModel(
        makerBps.multiply(scale, mc),
        takerBps.multiply(scale, mc),
        slippageBps.multiply(scale, mc));
  }

  /**
   * Multiplier to apply to the executed price for the taker side plus slippage on a market order.
   * For a BUY this widens the fill price upward; for a SELL it narrows it downward.
   */
  public BigDecimal totalAdverseFactor(MathContext mc) {
    BigDecimal totalBps = takerBps.add(slippageBps);
    return totalBps.divide(BigDecimal.valueOf(10_000), mc);
  }

  private static BigDecimal bps(String s) {
    return new BigDecimal(s);
  }
}
