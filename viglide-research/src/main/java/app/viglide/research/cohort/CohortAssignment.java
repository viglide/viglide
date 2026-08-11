package app.viglide.research.cohort;

import java.time.Instant;
import java.util.Objects;

/**
 * One pair's cohort membership as of one point in time — the unit {@link CohortAssigner} produces.
 * {@code advRank} is 1-based, 1 = highest average daily dollar volume in the universe at {@code
 * asOf}.
 */
public record CohortAssignment(String pair, Cohort cohort, Instant asOf, double adv, int advRank) {

  public CohortAssignment {
    Objects.requireNonNull(pair, "pair");
    Objects.requireNonNull(cohort, "cohort");
    Objects.requireNonNull(asOf, "asOf");
    if (pair.isBlank()) throw new IllegalArgumentException("pair must not be blank");
    if (advRank < 1) throw new IllegalArgumentException("advRank must be >= 1, got: " + advRank);
  }
}
