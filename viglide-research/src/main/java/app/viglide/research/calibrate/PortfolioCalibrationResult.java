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
    double cvUlcerIndexMedian) {

  public PortfolioCalibrationResult {
    Objects.requireNonNull(params, "params");
    Objects.requireNonNull(cvTotalReturnMedian, "cvTotalReturnMedian");
    Objects.requireNonNull(cvMaxDrawdownWorst, "cvMaxDrawdownWorst");
    Objects.requireNonNull(cvReturnOnDeployedCapitalPooled, "cvReturnOnDeployedCapitalPooled");
    params = Map.copyOf(new LinkedHashMap<>(params));
    if (foldsEvaluated < 1) {
      throw new IllegalArgumentException("foldsEvaluated must be >= 1");
    }
    if (cvTradeCountTotal < 0) {
      throw new IllegalArgumentException("cvTradeCountTotal must be >= 0");
    }
  }
}
