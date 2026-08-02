package app.viglide.research.calibrate;

import java.util.List;
import java.util.Objects;

/**
 * Full output of a {@link CalibrationHarness#run} invocation (PLAN-008 Task D.1): the ranked,
 * {@code minTrades}-filtered survivors, plus the trial accounting {@link
 * app.viglide.core.backtest.Metrics#deflatedSharpeRatio} needs.
 *
 * <p>{@code trials} is the count of candidates <em>evaluated</em> — not just survivors — and is
 * authoritative even when {@code --time-budget} truncates the search early ("record whatever
 * actually ran"). {@code trialSharpeVariancePerPeriod} is the population variance, across every
 * evaluated candidate, of {@code cvSharpeMedian / sqrt(365)} (per-period, not annualised).
 */
public record CalibrationRun(
    List<CalibrationResult> survivors, int trials, double trialSharpeVariancePerPeriod) {

  public CalibrationRun {
    Objects.requireNonNull(survivors, "survivors");
    survivors = List.copyOf(survivors);
    if (trials < 0) {
      throw new IllegalArgumentException("trials must be >= 0, got: " + trials);
    }
  }
}
