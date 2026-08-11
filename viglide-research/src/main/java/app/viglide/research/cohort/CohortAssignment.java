package app.viglide.research.cohort;

import java.time.Instant;
import java.util.Objects;

/**
 * One pair's cohort membership as of one point in time — the unit {@link CohortAssigner} produces.
 * {@code advRank} is 1-based, 1 = highest average daily dollar volume in the universe at {@code
 * asOf}.
 *
 * @param candlesInWindow how many candles the ADV was actually computed from. Carried so a caller
 *     can tell a real liquidity ranking from a tie-break artifact: at the corpus's first OOS window
 *     the trailing lookback lands entirely before the first bar, every ADV is 0, and the assignment
 *     is decided purely by the alphabetical tie-break. See {@link #degenerate()}.
 */
public record CohortAssignment(
    String pair, Cohort cohort, Instant asOf, double adv, int advRank, int candlesInWindow) {

  public CohortAssignment {
    Objects.requireNonNull(pair, "pair");
    Objects.requireNonNull(cohort, "cohort");
    Objects.requireNonNull(asOf, "asOf");
    if (pair.isBlank()) throw new IllegalArgumentException("pair must not be blank");
    if (advRank < 1) throw new IllegalArgumentException("advRank must be >= 1, got: " + advRank);
    if (candlesInWindow < 0) {
      throw new IllegalArgumentException("candlesInWindow must be >= 0, got: " + candlesInWindow);
    }
  }

  /**
   * Whether this assignment rests on no data at all — the lookback window was empty, so {@code adv}
   * is 0 and {@code cohort} reflects only the tie-break order. A window in which this holds for
   * every pair carries no liquidity information and must not be scored as if it did (PLAN-024 Task
   * E writes the per-hypothesis rule; this method exists so that rule can be enforced in code
   * rather than remembered).
   */
  public boolean degenerate() {
    return candlesInWindow == 0;
  }
}
