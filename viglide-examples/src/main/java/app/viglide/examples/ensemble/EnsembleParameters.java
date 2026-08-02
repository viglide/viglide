package app.viglide.examples.ensemble;

/**
 * Configuration for {@link EnsembleCombiner} (PLAN-003 §C.2, ADR-0003).
 *
 * <ul>
 *   <li>{@code minVotedConfidence} — the winning side's summed confidence must reach this floor for
 *       the ensemble to emit a non-HOLD signal. Prevents tiny majorities from triggering trades.
 *   <li>{@code expectedMaxConfidenceSum} — denominator for the output confidence; with three
 *       strategies and typical max single-strategy confidence ~0.83, 2.5 maps unanimous agreement
 *       to ≈ 1.0.
 * </ul>
 */
public record EnsembleParameters(double minVotedConfidence, double expectedMaxConfidenceSum) {

  /** Validates {@code 0 < minVotedConfidence <= expectedMaxConfidenceSum}. */
  public EnsembleParameters {
    if (minVotedConfidence <= 0.0) {
      throw new IllegalArgumentException(
          "minVotedConfidence must be > 0, got: " + minVotedConfidence);
    }
    if (expectedMaxConfidenceSum < minVotedConfidence) {
      throw new IllegalArgumentException("expectedMaxConfidenceSum must be >= minVotedConfidence");
    }
  }

  /** Defaults sized for a three-strategy ensemble: floor 0.5, denominator 2.5. */
  public static EnsembleParameters defaults() {
    return new EnsembleParameters(0.5, 2.5);
  }
}
