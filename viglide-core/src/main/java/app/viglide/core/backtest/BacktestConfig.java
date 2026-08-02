package app.viglide.core.backtest;

import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.ExchangeFilters;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Backtest tuning knobs. These are <strong>simulation</strong> parameters — they shape how the
 * virtual exchange behaves during replay. They are <strong>not</strong> the Layer-4 Risk Manager
 * (CLAUDE.md §7), which is a separate production component with hard limits.
 *
 * <p>{@code maxPositionPct} caps how much of available cash is committed per trade; PLAN-002
 * recommends 2% (matching the Layer-4 hard limit). {@code stopLossPct} and {@code takeProfitPct}
 * are mandatory in production but the backtest treats them as optional knobs — set them to {@code
 * null} to disable price-based exits and rely solely on strategy SELL signals.
 */
public record BacktestConfig(
    BigDecimal startingCash,
    FeeModel fees,
    int warmupBars,
    BigDecimal maxPositionPct,
    BigDecimal stopLossPct,
    BigDecimal takeProfitPct,
    /**
     * <strong>Vestigial (PLAN-011 Task J / F10):</strong> see {@link #barsPerYearFor}'s Javadoc —
     * recorded (and echoed into {@code result.json}) but never read by any Sharpe/PSR/DSR
     * arithmetic, so changing it never changes any computed statistic. Kept for backward
     * compatibility with existing constructor call sites rather than removed outright — a removal
     * would touch every harness/CLI/test that constructs a {@link BacktestConfig}, for a field with
     * no live defect. Do not add new logic that reads this field expecting it to affect
     * annualisation.
     */
    int barsPerYear,
    /**
     * PLAN-008 Task F: minimum bars a position must be held before a SELL signal is allowed to
     * close it — {@link app.viglide.core.backtest.FundingArbHarnessV2}-specific (0 = no hold
     * period, matching every other harness's existing SELL-executes-immediately behaviour).
     */
    int minHoldBars,
    /**
     * PLAN-010 Task D3: optional backtest/live-parity knob — when present, threaded into every
     * evaluation's {@code MarketContext} so the Risk Manager rounds sizes/prices exactly as it
     * would live (see {@link app.viglide.core.domain.ExchangeFilters}). Absent by default, so
     * existing research numbers are unchanged; a paper/live-parity run sets the pair's real
     * exchange values.
     */
    Optional<ExchangeFilters> exchangeFilters) {

  /** Validates ranges so misconfiguration fails fast. */
  public BacktestConfig {
    Objects.requireNonNull(startingCash, "startingCash");
    Objects.requireNonNull(fees, "fees");
    Objects.requireNonNull(maxPositionPct, "maxPositionPct");
    Objects.requireNonNull(exchangeFilters, "exchangeFilters");
    if (startingCash.signum() <= 0) {
      throw new IllegalArgumentException("startingCash must be > 0");
    }
    if (warmupBars < 1) {
      throw new IllegalArgumentException("warmupBars must be >= 1, got: " + warmupBars);
    }
    if (maxPositionPct.signum() <= 0 || maxPositionPct.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("maxPositionPct must be in (0, 1]");
    }
    if (stopLossPct != null && stopLossPct.signum() <= 0) {
      throw new IllegalArgumentException("stopLossPct must be > 0 when set");
    }
    if (takeProfitPct != null && takeProfitPct.signum() <= 0) {
      throw new IllegalArgumentException("takeProfitPct must be > 0 when set");
    }
    if (barsPerYear < 1) {
      throw new IllegalArgumentException("barsPerYear must be >= 1");
    }
    if (minHoldBars < 0) {
      throw new IllegalArgumentException("minHoldBars must be >= 0, got: " + minHoldBars);
    }
  }

  /**
   * Backward-compatible constructor for callers that predate {@code exchangeFilters} (PLAN-010 Task
   * D3). Defaults it absent.
   */
  public BacktestConfig(
      BigDecimal startingCash,
      FeeModel fees,
      int warmupBars,
      BigDecimal maxPositionPct,
      BigDecimal stopLossPct,
      BigDecimal takeProfitPct,
      int barsPerYear,
      int minHoldBars) {
    this(
        startingCash,
        fees,
        warmupBars,
        maxPositionPct,
        stopLossPct,
        takeProfitPct,
        barsPerYear,
        minHoldBars,
        Optional.empty());
  }

  /** Backward-compatible constructor for callers that predate {@code minHoldBars} (PLAN-008 F). */
  public BacktestConfig(
      BigDecimal startingCash,
      FeeModel fees,
      int warmupBars,
      BigDecimal maxPositionPct,
      BigDecimal stopLossPct,
      BigDecimal takeProfitPct,
      int barsPerYear) {
    this(
        startingCash, fees, warmupBars, maxPositionPct, stopLossPct, takeProfitPct, barsPerYear, 0);
  }

  /**
   * Defaults sized for the EMA(9/21)+RSI(14) strategy on hourly candles: 200-bar warmup (long
   * enough for the 21-period EMA to converge), 2% position size (CLAUDE.md §7 ceiling), 2%
   * stop-loss, 4% take-profit (2:1 reward/risk), 8760 bars/year (crypto 24×365).
   */
  public static BacktestConfig hourlyDefaults() {
    return new BacktestConfig(
        new BigDecimal("10000"),
        FeeModel.binanceDefault(),
        200,
        new BigDecimal("0.02"),
        new BigDecimal("0.02"),
        new BigDecimal("0.04"),
        8760);
  }

  /**
   * Derives the {@code barsPerYear} default for an arbitrary candle interval (PLAN-009 Task B0
   * audit + B1): {@code 365 days / interval.duration()}, e.g. 8760 for {@code ONE_HOUR} (matching
   * the historical hardcoded constant) or 525,600 for {@code ONE_MINUTE}. Note this field is
   * currently recorded as metadata only — see the Task B0 finding in {@code
   * docs/notes/2026-07-14-plan009-verdict.md} for why no Sharpe/PSR/DSR computation actually
   * consumes it (annualisation resamples to daily first, independent of bar interval).
   */
  public static int barsPerYearFor(CandleInterval interval) {
    Objects.requireNonNull(interval, "interval");
    return Math.toIntExact(Duration.ofDays(365).dividedBy(interval.duration()));
  }
}
