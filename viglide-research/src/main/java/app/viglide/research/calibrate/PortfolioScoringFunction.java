package app.viglide.research.calibrate;

/**
 * {@link ScoringFunction}'s cross-sectional counterpart (PLAN-019 Task D): ranks a {@link
 * app.viglide.core.calibrate.PortfolioCandidate} by a single {@code double} score, descending. Must
 * be a pure function of its input, same determinism requirement as {@link ScoringFunction}.
 *
 * <p>{@link #CARRY_YIELD} mirrors {@link ScoringFunction#CARRY_YIELD} exactly (pooled economic
 * yield penalised by drawdown depth) — the same F2 rationale applies unchanged to a pooled panel
 * result. {@link #MEDIAN_CV_SHARPE} is provided for parity but is <strong>not</strong> the
 * recommended default for a cross-sectional carry book, for the identical reason {@code
 * EconomicMetrics}'s own class Javadoc gives for the single-symbol case.
 */
@FunctionalInterface
public interface PortfolioScoringFunction {

  double score(PortfolioCalibrationResult result);

  PortfolioScoringFunction MEDIAN_CV_SHARPE = PortfolioCalibrationResult::cvSharpeMedian;

  PortfolioScoringFunction CARRY_YIELD =
      r -> r.cvReturnOnDeployedCapitalPooled().doubleValue() / (1.0 + r.cvUlcerIndexMedian());

  /** Resolves a named objective, same two names {@link ScoringFunction#byName} accepts. */
  static PortfolioScoringFunction byName(String name) {
    return switch (name) {
      case "median-cv-sharpe" -> MEDIAN_CV_SHARPE;
      case "carry-yield" -> CARRY_YIELD;
      default ->
          throw new IllegalArgumentException(
              "objective must be 'median-cv-sharpe' or 'carry-yield', got: '" + name + "'");
    };
  }
}
