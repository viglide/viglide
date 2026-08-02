package app.viglide.research.calibrate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregated cross-fold metrics for a single candidate. Median values smooth out single-fold
 * outliers; {@code cvMaxDrawdownWorst} is the worst (largest) drawdown across all folds because a
 * single catastrophic fold can disqualify a parameter set regardless of median performance.
 *
 * <p><strong>Naming (PLAN-008 Task D.2):</strong> fields are prefixed {@code cv} (cross-validation)
 * rather than {@code oos} (out-of-sample) — these are the metrics that candidate <em>selection</em>
 * optimised against, not a genuine held-out test. True OOS validation happens afterward, on data
 * the calibration run never saw at all (see {@code Metrics#probabilisticSharpeRatio} / {@code
 * #deflatedSharpeRatio} for the statistics that correct for having searched over many candidates).
 * Fields were named {@code oos*} before this rename; readers of persisted JSON (winners.json) must
 * still accept the old spelling — see {@link app.viglide.research.cli.Args#jsonFallback}.
 *
 * <p>{@code params} carries the strategy-specific parameter snapshot so this record is generic
 * across strategy families (PLAN-003 refactor).
 *
 * <p><strong>PLAN-013 Task D (review finding F4).</strong> {@code cvTradeCountTotal} is the sum of
 * every fold's trade count — the filter {@link CalibrationHarness} applies against {@code
 * minTrades} as of this task, replacing a median-across-folds filter that was near-inert for a
 * 1–2-trades-per-fold strategy (a median of mostly-zero counts is still zero, or close to it, no
 * matter how {@code minTrades} is set). {@code cvReturnOnDeployedCapitalPooled} pools every fold's
 * trades into one ratio rather than averaging per-fold ratios computed from too few trades each —
 * the same reasoning. {@code cvUlcerIndexMedian} is a median like the pre-existing fold metrics
 * (drawdown shape is meaningful per-fold, unlike a sparse trade-based ratio).
 */
public record CalibrationResult(
    Map<String, Object> params,
    double cvSharpeMedian,
    BigDecimal cvTotalReturnMedian,
    BigDecimal cvMaxDrawdownWorst,
    int cvTradeCountMedian,
    int foldsEvaluated,
    int cvTradeCountTotal,
    BigDecimal cvReturnOnDeployedCapitalPooled,
    double cvUlcerIndexMedian) {

  public CalibrationResult {
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
