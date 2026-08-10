package app.viglide.core.calibrate;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.spi.PortfolioStrategy;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * {@link Candidate}'s cross-sectional counterpart (PLAN-019 Task D): one concrete parameter set
 * under evaluation by {@code app.viglide.research.calibrate.PortfolioCalibrationHarness}, wrapping
 * a fully-built {@link PortfolioStrategy} instead of a single-symbol {@link
 * app.viglide.core.spi.TradingStrategy} — a {@code PortfolioStrategy} has no per-symbol identity to
 * attach a candle history to at construction time, so this exists as its own type rather than
 * reusing {@link Candidate}.
 */
public record PortfolioCandidate(
    PortfolioStrategy strategy,
    Map<String, Object> paramsSnapshot,
    UnaryOperator<BacktestConfig> configOverride,
    BigDecimal noTradeBand) {

  public PortfolioCandidate {
    Objects.requireNonNull(strategy, "strategy");
    Objects.requireNonNull(paramsSnapshot, "paramsSnapshot");
    paramsSnapshot = Map.copyOf(new LinkedHashMap<>(paramsSnapshot));
    if (configOverride == null) configOverride = UnaryOperator.identity();
    // Same bound PortfolioBacktestHarness#runTargets enforces, checked here so a malformed grid
    // fails when the candidate is built rather than part-way through the first fold that runs it.
    if (noTradeBand != null && noTradeBand.signum() < 0) {
      throw new IllegalArgumentException("noTradeBand must be >= 0, got: " + noTradeBand);
    }
  }

  /** Backward-compatible constructor defaulting {@code configOverride} to identity, no override. */
  public PortfolioCandidate(PortfolioStrategy strategy, Map<String, Object> paramsSnapshot) {
    this(strategy, paramsSnapshot, UnaryOperator.identity(), null);
  }

  /**
   * Backward-compatible constructor for callers that only need to vary {@code configOverride}
   * (PLAN-021 Task B added the {@code noTradeBand} component; this keeps the 3-arg shape every
   * pre-existing call site used compiling unchanged).
   */
  public PortfolioCandidate(
      PortfolioStrategy strategy,
      Map<String, Object> paramsSnapshot,
      UnaryOperator<BacktestConfig> configOverride) {
    this(strategy, paramsSnapshot, configOverride, null);
  }
}
