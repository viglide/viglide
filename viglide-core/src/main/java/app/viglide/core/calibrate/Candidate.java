package app.viglide.core.calibrate;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.spi.TradingStrategy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * One concrete parameter set under evaluation by the calibration harness ({@code
 * app.viglide.research.calibrate.CalibrationHarness}): a fully-built {@link TradingStrategy}
 * together with an ordered snapshot of its parameters (kept as JSON-friendly primitives) so the
 * calibration output can carry per-strategy fields without the harness needing to know the
 * strategy's parameter shape.
 *
 * <p>{@code configOverride} (PLAN-008 Task F) lets a parameter space vary harness-level knobs (e.g.
 * {@link BacktestConfig#minHoldBars()}) per candidate, not just strategy parameters — applied by
 * the harness to the shared {@link BacktestConfig} before that candidate's folds run. Defaults to
 * identity; every parameter space except the funding-arb one (private, {@code
 * app.viglide.strategies}) leaves it untouched.
 */
public record Candidate(
    TradingStrategy strategy,
    Map<String, Object> paramsSnapshot,
    UnaryOperator<BacktestConfig> configOverride) {

  public Candidate {
    Objects.requireNonNull(strategy, "strategy");
    Objects.requireNonNull(paramsSnapshot, "paramsSnapshot");
    paramsSnapshot = Map.copyOf(new LinkedHashMap<>(paramsSnapshot));
    if (configOverride == null) configOverride = UnaryOperator.identity();
  }

  /** Backward-compatible constructor for callers that predate {@code configOverride}. */
  public Candidate(TradingStrategy strategy, Map<String, Object> paramsSnapshot) {
    this(strategy, paramsSnapshot, UnaryOperator.identity());
  }
}
