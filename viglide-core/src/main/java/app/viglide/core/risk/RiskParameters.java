package app.viglide.core.risk;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Tuning knobs for the Layer-4 Risk Manager (PLAN-005b, ADR-0006, CLAUDE.md §7). All values are
 * mandatory; the compact constructor rejects misconfiguration so the RM fails loudly instead of
 * silently using a bad default.
 *
 * <p>The production values live in GCP Secret Manager; this record holds the backtest / testing
 * defaults.
 */
public record RiskParameters(
    /** Maximum fraction of portfolio equity committed per trade. Hard limit: 2% (0.02). */
    BigDecimal maxPositionPct,
    /** Maximum fraction of portfolio equity that can be lost before the circuit breaker trips. */
    BigDecimal maxPortfolioDrawdownPct,
    /** Maximum leverage ratio (total open notional / equity). Hard limit: 2×. */
    BigDecimal maxLeverage,
    /** Maximum fraction of the pair's daily volume a single position can represent. */
    BigDecimal maxDailyVolumePct,
    /**
     * Maximum fraction of portfolio equity risked per trade. The RM sizes positions so that a
     * stop-loss hit costs at most this fraction of equity (e.g. 0.005 = 0.5%).
     */
    BigDecimal maxPortfolioRiskPct,
    /** Multiplier on ATR(14) for stop-loss distance. Wilder classic is 2.0. */
    double stopLossAtrMult,
    /**
     * Minimum signal confidence accepted for execution. Signals below this threshold are refused
     * with {@link ExecutionDecision.RefusalReason#CONFIDENCE_BELOW_THRESHOLD}.
     */
    double confidenceFloor,
    /**
     * Maximum age of a signal (gate time minus {@code TechnicalSignal.asOf()}) before it is refused
     * as stale (CLAUDE.md §7: "if any input required for a risk check is missing or stale, do not
     * trade"). Backtests bind the Risk Manager's {@link java.time.Clock} to simulated bar time (see
     * {@link BacktestClockSync}), so this never trips spuriously on historical replay.
     */
    Duration maxStaleInputAge,
    /**
     * Hard cap on total notional deployed across all open positions, in quote currency (PLAN-012
     * Task F). {@link Optional#empty()} disables this check — the default, so every existing test
     * and backtest is unaffected. Binds independently of, and in addition to, {@link #maxLeverage}
     * — a percentage-of-equity cap is legible on a large account and illegibly small on a small
     * one; this is the static, easy-to-reason-about ceiling a live-phase operator sets directly.
     */
    Optional<BigDecimal> maxTotalDeployedAbs,
    /**
     * Hard cap per position, in quote currency (PLAN-012 Task F). {@link Optional#empty()} disables
     * this check (default). Unlike {@link #maxPositionPct}'s *silent* cap (ADR-0006 amendment, F8 —
     * size is truncated, not refused), a breach of this absolute limit is a hard {@link
     * ExecutionDecision.RefusalReason#ABSOLUTE_EXPOSURE_CAP_EXCEEDED} refusal.
     */
    Optional<BigDecimal> maxPositionAbs,
    /**
     * Halts new entries for the remainder of the UTC day once realised-plus-unrealised loss since
     * the day's start reaches this absolute amount, in quote currency (PLAN-012 Task F). {@link
     * Optional#empty()} disables this check (default) — it also never fires unless the caller's
     * {@link PortfolioState#dayStartEquity()} is itself present, so a caller that doesn't track a
     * day boundary (every backtest harness) is unaffected even if this limit is configured.
     */
    Optional<BigDecimal> maxDailyLossAbs,
    /**
     * Halts trading <strong>permanently</strong> (operator restart required, same semantics as
     * {@link ExecutionDecision.RefusalReason#CIRCUIT_BREAKER_TRIPPED}) once cumulative loss from
     * campaign-start equity reaches this absolute amount, in quote currency (PLAN-012 Task F).
     * {@link Optional#empty()} disables this check (default). Anchored to the equity the campaign
     * started with, not to {@link PortfolioState#peakEquity()} — "don't let me lose more than $X of
     * what I funded this with, ever" is a different, non-redundant rail from the existing
     * peak-anchored percentage breaker, which this deliberately mirrors in dollar terms rather than
     * duplicating with a new mechanism (see {@code CircuitBreaker#shouldTripAbsolute}).
     */
    Optional<BigDecimal> maxCampaignLossAbs) {

  /** 90 minutes ≈ 1.5 hourly bars — generous enough to absorb one missed bar plus jitter. */
  private static final Duration DEFAULT_MAX_STALE_INPUT_AGE = Duration.ofMinutes(90);

  /** Validates that all values are positive and within sensible ranges. */
  public RiskParameters {
    Objects.requireNonNull(maxPositionPct, "maxPositionPct");
    Objects.requireNonNull(maxPortfolioDrawdownPct, "maxPortfolioDrawdownPct");
    Objects.requireNonNull(maxLeverage, "maxLeverage");
    Objects.requireNonNull(maxDailyVolumePct, "maxDailyVolumePct");
    Objects.requireNonNull(maxPortfolioRiskPct, "maxPortfolioRiskPct");
    Objects.requireNonNull(maxStaleInputAge, "maxStaleInputAge");
    Objects.requireNonNull(maxTotalDeployedAbs, "maxTotalDeployedAbs");
    Objects.requireNonNull(maxPositionAbs, "maxPositionAbs");
    Objects.requireNonNull(maxDailyLossAbs, "maxDailyLossAbs");
    Objects.requireNonNull(maxCampaignLossAbs, "maxCampaignLossAbs");
    requirePositiveFraction(maxPositionPct, "maxPositionPct");
    requirePositiveFraction(maxPortfolioDrawdownPct, "maxPortfolioDrawdownPct");
    requirePositiveFraction(maxDailyVolumePct, "maxDailyVolumePct");
    requirePositiveFraction(maxPortfolioRiskPct, "maxPortfolioRiskPct");
    if (maxLeverage.signum() <= 0) {
      throw new IllegalArgumentException("maxLeverage must be > 0, got: " + maxLeverage);
    }
    if (stopLossAtrMult <= 0) {
      throw new IllegalArgumentException("stopLossAtrMult must be > 0, got: " + stopLossAtrMult);
    }
    if (confidenceFloor < 0 || confidenceFloor > 1) {
      throw new IllegalArgumentException(
          "confidenceFloor must be in [0,1], got: " + confidenceFloor);
    }
    if (maxStaleInputAge.isZero() || maxStaleInputAge.isNegative()) {
      throw new IllegalArgumentException("maxStaleInputAge must be > 0, got: " + maxStaleInputAge);
    }
    requirePositiveIfPresent(maxTotalDeployedAbs, "maxTotalDeployedAbs");
    requirePositiveIfPresent(maxPositionAbs, "maxPositionAbs");
    requirePositiveIfPresent(maxDailyLossAbs, "maxDailyLossAbs");
    requirePositiveIfPresent(maxCampaignLossAbs, "maxCampaignLossAbs");
    if (maxPositionAbs.isPresent()
        && maxTotalDeployedAbs.isPresent()
        && maxPositionAbs.get().compareTo(maxTotalDeployedAbs.get()) > 0) {
      throw new IllegalArgumentException(
          "maxPositionAbs ("
              + maxPositionAbs.get()
              + ") must be <= maxTotalDeployedAbs ("
              + maxTotalDeployedAbs.get()
              + ") -- a single position could never legally reach a per-position cap larger than"
              + " the whole portfolio's cap");
    }
  }

  /**
   * {@link #defaults()} plus the four absolute-dollar live-phase limits (PLAN-012 Task F).
   * CLAUDE.md §7's limits are all percentages, which are correct but, on a small real account,
   * produce dollar amounts too small to reason about without redoing the multiplication every time;
   * these bind independently of, and in addition to, the percentage limits — never in place of
   * them. Values are supplied by the caller (typically {@code viglide-runtime}, from the owner's
   * own configuration) — this method never invents a default dollar figure.
   */
  public static RiskParameters defaultsWithAbsoluteLimits(
      Optional<BigDecimal> maxTotalDeployedAbs,
      Optional<BigDecimal> maxPositionAbs,
      Optional<BigDecimal> maxDailyLossAbs,
      Optional<BigDecimal> maxCampaignLossAbs) {
    RiskParameters d = defaults();
    return new RiskParameters(
        d.maxPositionPct(),
        d.maxPortfolioDrawdownPct(),
        d.maxLeverage(),
        d.maxDailyVolumePct(),
        d.maxPortfolioRiskPct(),
        d.stopLossAtrMult(),
        d.confidenceFloor(),
        d.maxStaleInputAge(),
        maxTotalDeployedAbs,
        maxPositionAbs,
        maxDailyLossAbs,
        maxCampaignLossAbs);
  }

  /**
   * Production-grade defaults matching CLAUDE.md §7 hard limits:
   *
   * <ul>
   *   <li>2% max position size
   *   <li>15% max portfolio drawdown (circuit breaker)
   *   <li>2× max leverage
   *   <li>0.5% max daily volume fraction
   *   <li>0.5% max portfolio risk per trade
   *   <li>ATR(14) × 2.0 stop-loss
   *   <li>0.5 confidence floor
   *   <li>90-minute max signal staleness
   * </ul>
   */
  public static RiskParameters defaults() {
    return new RiskParameters(
        new BigDecimal("0.02"),
        new BigDecimal("0.15"),
        new BigDecimal("2.0"),
        new BigDecimal("0.005"),
        new BigDecimal("0.005"),
        2.0,
        0.5,
        DEFAULT_MAX_STALE_INPUT_AGE,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private static void requirePositiveFraction(BigDecimal v, String name) {
    if (v.signum() <= 0 || v.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException(name + " must be in (0, 1], got: " + v);
    }
  }

  private static void requirePositiveIfPresent(Optional<BigDecimal> v, String name) {
    if (v.isPresent() && v.get().signum() <= 0) {
      throw new IllegalArgumentException(name + " must be > 0 when present, got: " + v.get());
    }
  }
}
