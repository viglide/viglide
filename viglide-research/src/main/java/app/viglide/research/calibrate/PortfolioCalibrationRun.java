package app.viglide.research.calibrate;

import java.util.List;
import java.util.Objects;

/**
 * {@link CalibrationRun}'s cross-sectional counterpart (PLAN-022 Task A): the ranked, {@code
 * minTrades}-filtered survivors from a {@link PortfolioCalibrationHarness#run} invocation, plus how
 * many candidates were actually evaluated.
 *
 * <p>{@code trials} is authoritative even when {@code timeBudget} truncates the sweep early — a
 * time-budget cutoff must be visible here ({@code trials < } the candidate list's own size), never
 * silent, so a truncated search is never mistaken for a completed one (PLAN-022 Task A).
 */
public record PortfolioCalibrationRun(List<PortfolioCalibrationResult> survivors, int trials) {

  public PortfolioCalibrationRun {
    Objects.requireNonNull(survivors, "survivors");
    survivors = List.copyOf(survivors);
    if (trials < 0) {
      throw new IllegalArgumentException("trials must be >= 0, got: " + trials);
    }
  }
}
