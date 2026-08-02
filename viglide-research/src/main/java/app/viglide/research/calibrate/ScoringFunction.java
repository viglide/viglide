package app.viglide.research.calibrate;

/**
 * Ranks a calibration candidate by a single {@code double} score, descending (PLAN-013 Task D,
 * review finding F4). Must be a <strong>pure function</strong> of its input — {@link
 * CalibrationHarness}'s determinism guarantee (NFR-7: byte-identical ranking for a fixed dataset,
 * fold split, and seed) depends on it.
 *
 * <p>{@link #MEDIAN_CV_SHARPE} is the pre-PLAN-013 default, kept exactly as it was for historical
 * reproducibility. {@link #CARRY_YIELD} is F2's replacement: {@code cvSharpeMedian} is an
 * idle-day-diluted statistic (a curve flat except for a few same-sign accrual days manufactures a
 * high Sharpe regardless of how much was actually earned — see {@code EconomicMetrics}'s class
 * Javadoc); ranking by the pooled economic yield the candidate actually produced, penalised by how
 * deep its drawdowns were, does not have that failure mode.
 */
@FunctionalInterface
public interface ScoringFunction {

  double score(CalibrationResult result);

  /** Pre-PLAN-013 default. Kept for byte-identical reproducibility of historical runs. */
  ScoringFunction MEDIAN_CV_SHARPE = CalibrationResult::cvSharpeMedian;

  /**
   * {@code returnOnDeployedCapital}, pooled across every fold's trades (not a median of per-fold
   * ratios — most folds evaluate too few trades for a per-fold ratio to mean anything, exactly the
   * F2 problem this objective exists to avoid), divided by {@code 1 + medianUlcerIndex} — a
   * Martin-ratio-style penalty for drawdown depth, {@code +1} so a candidate with zero measured
   * drawdown does not produce an undefined or explosively large score.
   */
  ScoringFunction CARRY_YIELD =
      r -> r.cvReturnOnDeployedCapitalPooled().doubleValue() / (1.0 + r.cvUlcerIndexMedian());

  /**
   * Resolves a named objective from the {@code --objective} CLI flag — {@code "median-cv-sharpe"}
   * or {@code "carry-yield"}. Throws {@link IllegalArgumentException} on anything else, listing
   * both valid names, rather than silently defaulting.
   */
  static ScoringFunction byName(String name) {
    return switch (name) {
      case "median-cv-sharpe" -> MEDIAN_CV_SHARPE;
      case "carry-yield" -> CARRY_YIELD;
      default ->
          throw new IllegalArgumentException(
              "--objective must be 'median-cv-sharpe' or 'carry-yield', got: '" + name + "'");
    };
  }
}
