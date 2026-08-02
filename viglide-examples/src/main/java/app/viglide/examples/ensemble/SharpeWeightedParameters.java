package app.viglide.examples.ensemble;

import java.util.Map;
import java.util.Objects;

/**
 * Configuration for {@link SharpeWeightedCombiner} (PLAN-005a, ADR-0005).
 *
 * <ul>
 *   <li>{@code sharpeByStrategy} — strategy metadata name → recent Sharpe (from {@code
 *       winners.json["cvSharpeMedian"]}, falling back to the legacy {@code "oosSharpeMedian"} key —
 *       PLAN-008 Task D.2). Missing entries are treated as Sharpe = 0.
 *   <li>{@code concentration} — the α exponent in {@code max(0, sharpe)^α}. {@code α = 1} gives
 *       linear Sharpe-weighting; {@code α = 2} concentrates more weight on top performers; {@code α
 *       = 0} gives equal-weight (every non-negative-Sharpe strategy contributes equally).
 *   <li>{@code minVotedConfidence} — the winning side's weighted confidence sum must reach this
 *       floor; prevents a single low-confidence winner from triggering trades.
 *   <li>{@code expectedMaxConfidenceSum} — denominator for the output confidence. With normalised
 *       weights (sum = 1), the maximum weighted confidence sum is ≤ 1.0, so this should typically
 *       be set to 1.0.
 * </ul>
 */
public record SharpeWeightedParameters(
    Map<String, Double> sharpeByStrategy,
    double concentration,
    double minVotedConfidence,
    double expectedMaxConfidenceSum) {

  /** Validates field invariants and makes the Sharpe map immutable. */
  public SharpeWeightedParameters {
    Objects.requireNonNull(sharpeByStrategy, "sharpeByStrategy");
    if (concentration < 0.0) {
      throw new IllegalArgumentException("concentration must be >= 0, got: " + concentration);
    }
    if (minVotedConfidence <= 0.0) {
      throw new IllegalArgumentException(
          "minVotedConfidence must be > 0, got: " + minVotedConfidence);
    }
    // F16: checked directly rather than relying on the transitive implication from the
    // minVotedConfidence check below (expectedMaxConfidenceSum >= minVotedConfidence > 0).
    if (expectedMaxConfidenceSum <= 0.0) {
      throw new IllegalArgumentException(
          "expectedMaxConfidenceSum must be > 0, got: " + expectedMaxConfidenceSum);
    }
    if (expectedMaxConfidenceSum < minVotedConfidence) {
      throw new IllegalArgumentException("expectedMaxConfidenceSum must be >= minVotedConfidence");
    }
    sharpeByStrategy = Map.copyOf(sharpeByStrategy);
  }

  /**
   * Defaults for a four-strategy ensemble with Sharpe-weighted voting: {@code α = 1}, floor 0.5,
   * denominator 1.0 (appropriate for normalised weights).
   */
  public static SharpeWeightedParameters defaults() {
    return new SharpeWeightedParameters(Map.of(), 1.0, 0.5, 1.0);
  }
}
