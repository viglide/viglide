package app.viglide.core.risk;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Portfolio-level drawdown circuit breaker (PLAN-005b Task C, ADR-0006). When the peak-to-trough
 * drawdown exceeds {@code maxDrawdownPct}, all trading is halted for the rest of the run; the
 * {@link PortfolioState#circuitBreakerTripped()} flag is set and every subsequent gate call returns
 * {@link ExecutionDecision.RefusalReason#CIRCUIT_BREAKER_TRIPPED}.
 *
 * <p>This class is a pure static utility — no state, deterministic (NFR-7).
 */
public final class CircuitBreaker {

  private CircuitBreaker() {}

  /**
   * Returns {@code true} when the drawdown from peak exceeds the configured maximum.
   *
   * <p>Formula: {@code (peakEquity − currentEquity) / peakEquity ≥ maxDrawdownPct}.
   *
   * @param currentEquity current mark-to-market portfolio value (≥ 0)
   * @param peakEquity historical maximum portfolio value (> 0)
   * @param maxDrawdownPct the threshold fraction (e.g. {@code 0.15} for 15%)
   * @throws IllegalArgumentException if peakEquity ≤ 0 or maxDrawdownPct ≤ 0
   */
  public static boolean shouldTrip(
      BigDecimal currentEquity, BigDecimal peakEquity, BigDecimal maxDrawdownPct) {
    Objects.requireNonNull(currentEquity, "currentEquity");
    Objects.requireNonNull(peakEquity, "peakEquity");
    Objects.requireNonNull(maxDrawdownPct, "maxDrawdownPct");
    if (peakEquity.signum() <= 0) {
      throw new IllegalArgumentException("peakEquity must be > 0, got: " + peakEquity);
    }
    if (maxDrawdownPct.signum() <= 0) {
      throw new IllegalArgumentException("maxDrawdownPct must be > 0, got: " + maxDrawdownPct);
    }
    if (currentEquity.signum() < 0) {
      return true; // equity gone entirely negative — always trip
    }
    BigDecimal drawdown =
        peakEquity.subtract(currentEquity).divide(peakEquity, java.math.MathContext.DECIMAL128);
    return drawdown.compareTo(maxDrawdownPct) >= 0;
  }

  /**
   * Absolute-dollar sibling of {@link #shouldTrip} (PLAN-012 Task F, the {@code maxCampaignLossAbs}
   * live-phase limit) — same {@code CIRCUIT_BREAKER_TRIPPED} consequence and the same "not inside
   * the RM, tracked by whoever owns the flag" placement (see this class's own Javadoc), just an
   * absolute loss amount instead of a fraction of peak. Deliberately takes a caller-supplied {@code
   * referenceEquity} rather than always comparing against a peak — ADR-0015 anchors the
   * campaign-loss limit to <em>campaign-start</em> equity ("don't lose more than $X of what I
   * funded this with, ever"), a different and non-redundant rail from the peak-anchored percentage
   * breaker above, not a duplicate of it.
   *
   * <p>Formula: {@code referenceEquity − currentEquity ≥ maxLossAbs}.
   *
   * @param referenceEquity the equity this loss is measured from — e.g. campaign-start equity, not
   *     necessarily a running peak
   * @throws IllegalArgumentException if referenceEquity or maxLossAbs ≤ 0
   */
  public static boolean shouldTripAbsolute(
      BigDecimal currentEquity, BigDecimal referenceEquity, BigDecimal maxLossAbs) {
    Objects.requireNonNull(currentEquity, "currentEquity");
    Objects.requireNonNull(referenceEquity, "referenceEquity");
    Objects.requireNonNull(maxLossAbs, "maxLossAbs");
    if (referenceEquity.signum() <= 0) {
      throw new IllegalArgumentException("referenceEquity must be > 0, got: " + referenceEquity);
    }
    if (maxLossAbs.signum() <= 0) {
      throw new IllegalArgumentException("maxLossAbs must be > 0, got: " + maxLossAbs);
    }
    BigDecimal loss = referenceEquity.subtract(currentEquity, java.math.MathContext.DECIMAL128);
    return loss.compareTo(maxLossAbs) >= 0;
  }
}
