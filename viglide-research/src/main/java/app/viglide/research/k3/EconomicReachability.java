package app.viglide.research.k3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * S4 — economic reachability (ADR-0028), the gate that keeps K3 falsifiable: not "is this
 * profitable" but "does there exist a probability threshold at which acting on this signal clears
 * the round-trip friction floor." Sweeping thresholds is deliberate — {@code fundingarb} is a real
 * edge that fails at every threshold because its edge itself, not its trade frequency, is too
 * small; a signal that only clears the floor by trading rarely is not disqualified by that alone.
 *
 * <p><strong>The surviving sample is floored per pair, not pooled (ADR-0027 D1).</strong> S6's
 * per-pair floor constrains the <em>whole</em> sample and says nothing about the high-probability
 * subset a given threshold selects, so it cannot stop a threshold from "clearing" the floor on a
 * handful of observations concentrated in one pair-year while S6 is satisfied by entirely
 * different, lower-probability observations. {@link #reachable} therefore applies its own floor to
 * each threshold's <em>surviving</em> observations, per pair, and requires several pairs to clear
 * it — a reachable trading rule is one that could be run, not one that fired three times on BTC in
 * 2021.
 */
public final class EconomicReachability {

  private EconomicReachability() {}

  /**
   * One swept threshold: how many observations fired at or above it, their mean directional edge,
   * and how those surviving observations are distributed across pairs.
   *
   * @param tradeCountByPair surviving observations per pair, sorted by pair; pairs with none at
   *     this threshold are absent rather than present with zero
   */
  public record ReachabilityPoint(
      double probabilityThreshold,
      int tradeCount,
      double edgeBps,
      Map<String, Integer> tradeCountByPair) {

    public ReachabilityPoint {
      tradeCountByPair = Map.copyOf(tradeCountByPair);
    }

    /** How many pairs contribute at least {@code minTradeCountPerPair} surviving observations. */
    public long pairsWithAtLeast(int minTradeCountPerPair) {
      return tradeCountByPair.values().stream().filter(c -> c >= minTradeCountPerPair).count();
    }
  }

  /**
   * @param observations every fired signal in the sample
   * @param thresholds probability thresholds to sweep, e.g. {0.5, 0.55, ..., 0.9}
   */
  public static List<ReachabilityPoint> sweep(
      List<SignalObservation> observations, double[] thresholds) {
    List<ReachabilityPoint> out = new ArrayList<>();
    for (double threshold : thresholds) {
      List<SignalObservation> filtered =
          observations.stream().filter(o -> o.probability() >= threshold).toList();
      double edgeBps =
          filtered.isEmpty()
              ? 0.0
              : filtered.stream()
                      .mapToDouble(SignalObservation::directionalReturn)
                      .average()
                      .orElse(0.0)
                  * 10_000.0;
      Map<String, Integer> byPair = new TreeMap<>();
      for (SignalObservation o : filtered) {
        byPair.merge(o.pair(), 1, Integer::sum);
      }
      out.add(new ReachabilityPoint(threshold, filtered.size(), edgeBps, byPair));
    }
    return List.copyOf(out);
  }

  /**
   * S4's pass/fail: does any swept threshold clear {@code floorBps} on a sample broad enough to act
   * on — at least {@code minQualifyingPairs} distinct pairs each contributing at least {@code
   * minTradeCountPerPair} of that threshold's surviving observations.
   *
   * <p>Both floors apply to the surviving subset, deliberately: see the class Javadoc for why S6
   * cannot serve this purpose.
   */
  public static boolean reachable(
      List<ReachabilityPoint> points,
      double floorBps,
      int minTradeCountPerPair,
      int minQualifyingPairs) {
    return points.stream()
        .anyMatch(
            p ->
                p.edgeBps() >= floorBps
                    && p.pairsWithAtLeast(minTradeCountPerPair) >= minQualifyingPairs);
  }
}
