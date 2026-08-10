package app.viglide.research.calibrate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link CalibrationResult}'s cross-sectional counterpart (PLAN-019 Task D) — aggregated cross-fold
 * metrics for one {@link app.viglide.core.calibrate.PortfolioCandidate}, pooled across every symbol
 * in the panel per fold rather than one symbol at a time. Field names and meanings mirror {@link
 * CalibrationResult} exactly so the two remain readable side by side.
 */
public record PortfolioCalibrationResult(
    Map<String, Object> params,
    double cvSharpeMedian,
    BigDecimal cvTotalReturnMedian,
    BigDecimal cvMaxDrawdownWorst,
    int cvTradeCountTotal,
    int foldsEvaluated,
    BigDecimal cvReturnOnDeployedCapitalPooled,
    double cvUlcerIndexMedian,
    Map<String, Integer> carryEligibilityGapsBySymbol) {

  /**
   * @param carryEligibilityGapsBySymbol PLAN-022 Task C (N1): number of folds, per symbol, where
   *     that symbol had an overall spot series declared but this fold's own slice of it came up
   *     empty (a data gap), so the fold ran it single-leg-eligible instead of silently registering
   *     it carry-capable with no actual spot bars. Empty map means no such gap occurred anywhere in
   *     this run — the common case. Visible here, not only in {@code
   *     BacktestResult#diagnostics()}'s per-fold {@code barsSkipped.<SYMBOL>}, which this harness
   *     never surfaces at all.
   */
  public PortfolioCalibrationResult {
    Objects.requireNonNull(params, "params");
    Objects.requireNonNull(cvTotalReturnMedian, "cvTotalReturnMedian");
    Objects.requireNonNull(cvMaxDrawdownWorst, "cvMaxDrawdownWorst");
    Objects.requireNonNull(cvReturnOnDeployedCapitalPooled, "cvReturnOnDeployedCapitalPooled");
    Objects.requireNonNull(carryEligibilityGapsBySymbol, "carryEligibilityGapsBySymbol");
    params = Map.copyOf(new LinkedHashMap<>(params));
    carryEligibilityGapsBySymbol = Map.copyOf(new LinkedHashMap<>(carryEligibilityGapsBySymbol));
    if (foldsEvaluated < 1) {
      throw new IllegalArgumentException("foldsEvaluated must be >= 1");
    }
    if (cvTradeCountTotal < 0) {
      throw new IllegalArgumentException("cvTradeCountTotal must be >= 0");
    }
  }
}
