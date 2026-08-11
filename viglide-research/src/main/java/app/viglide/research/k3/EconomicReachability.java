package app.viglide.research.k3;

import java.util.ArrayList;
import java.util.List;

/**
 * S4 — economic reachability (ADR-0028), the gate that keeps K3 falsifiable: not "is this
 * profitable" but "does there exist a probability threshold at which acting on this signal clears
 * the round-trip friction floor." Sweeping thresholds is deliberate — {@code fundingarb} is a real
 * edge that fails at every threshold because its edge itself, not its trade frequency, is too
 * small; a signal that only clears the floor by trading rarely is not disqualified by that alone
 * (S6 still requires enough surviving observations to trust the number, so a threshold cannot pass
 * on 3 lucky fills).
 */
public final class EconomicReachability {

  private EconomicReachability() {}

  /**
   * One swept threshold: how many observations fired at or above it, and their mean directional
   * edge.
   */
  public record ReachabilityPoint(double probabilityThreshold, int tradeCount, double edgeBps) {}

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
      out.add(new ReachabilityPoint(threshold, filtered.size(), edgeBps));
    }
    return List.copyOf(out);
  }

  /**
   * S4's pass/fail: does any swept threshold clear {@code floorBps} while retaining at least {@code
   * minTradeCount} observations.
   */
  public static boolean reachable(
      List<ReachabilityPoint> points, double floorBps, int minTradeCount) {
    return points.stream()
        .anyMatch(p -> p.tradeCount() >= minTradeCount && p.edgeBps() >= floorBps);
  }
}
