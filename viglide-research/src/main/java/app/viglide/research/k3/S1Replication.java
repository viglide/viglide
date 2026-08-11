package app.viglide.research.k3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * S1's replication rule (ADR-0028), same shape as ADR-0016 condition 5: a (pair, year) cell
 * "qualifies" if its rank IC is positive and beats its own cell-level circular-permutation null at
 * the 95th percentile; the gate passes only if at least {@code minPairs} distinct pairs each
 * qualify in at least {@code minYearsPerPair} distinct years. A pair that qualifies once, in one
 * lucky year, does not count — this is the exact defect the K1′ condition-5 replication requirement
 * exists to rule out, per {@code docs/notes/2026-08-11-plan016-k1-prime-verdict.md} (viglide-lab).
 */
public final class S1Replication {

  private S1Replication() {}

  /**
   * One (pair, year) cell's own rank IC and where it falls in its own circular-permutation null.
   */
  public record CellResult(
      String pair, int year, double ic, double nullPercentile, int observationCount) {

    public boolean qualifies() {
      return ic > 0.0 && nullPercentile >= 0.95;
    }
  }

  /**
   * @param observationsByPairAndYear every fired observation, grouped by pair then OOS year
   * @param nullN circular-permutation count per cell (ADR-0028 S3: 200)
   * @param seed base seed; each cell's own permutation run is seeded deterministically off it plus
   *     the pair/year so the whole evaluation is reproducible regardless of map iteration order
   */
  public static List<CellResult> evaluateCells(
      Map<String, Map<Integer, List<SignalObservation>>> observationsByPairAndYear,
      int nullN,
      long seed) {
    List<CellResult> out = new ArrayList<>();
    for (Map.Entry<String, Map<Integer, List<SignalObservation>>> pairEntry :
        new TreeMap<>(observationsByPairAndYear).entrySet()) {
      String pair = pairEntry.getKey();
      for (Map.Entry<Integer, List<SignalObservation>> yearEntry :
          new TreeMap<>(pairEntry.getValue()).entrySet()) {
        int year = yearEntry.getKey();
        List<SignalObservation> cell = yearEntry.getValue();
        if (cell.size() < 2) {
          out.add(new CellResult(pair, year, 0.0, 0.0, cell.size()));
          continue;
        }
        double[] predicted = cell.stream().mapToDouble(SignalObservation::probability).toArray();
        double[] realized =
            cell.stream().mapToDouble(SignalObservation::directionalReturn).toArray();
        long cellSeed = seed ^ ((long) pair.hashCode() * 31 + year);
        CircularPermutationNullModel.NullModelResult nullResult =
            CircularPermutationNullModel.run(
                predicted, realized, RankIc::spearman, nullN, cellSeed);
        out.add(
            new CellResult(
                pair, year, nullResult.realStatistic(), nullResult.percentile(), cell.size()));
      }
    }
    return List.copyOf(out);
  }

  /**
   * The aggregation rule itself: qualifying cells reduced to "at least {@code minPairs} pairs, each
   * qualifying in at least {@code minYearsPerPair} distinct years."
   */
  public static boolean passesReplication(
      List<CellResult> cells, int minPairs, int minYearsPerPair) {
    Map<String, Set<Integer>> qualifyingYearsByPair = new HashMap<>();
    for (CellResult cell : cells) {
      if (cell.qualifies()) {
        qualifyingYearsByPair.computeIfAbsent(cell.pair(), p -> new HashSet<>()).add(cell.year());
      }
    }
    long qualifyingPairs =
        qualifyingYearsByPair.values().stream()
            .filter(years -> years.size() >= minYearsPerPair)
            .count();
    return qualifyingPairs >= minPairs;
  }
}
