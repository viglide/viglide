package app.viglide.research.k3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ties S1, S2, S3, S4 and S6 together into one pre-registered verdict (ADR-0028, PLAN-024 Task D).
 *
 * <p><strong>S5 is not computed here.</strong> "Cohorts declared ex ante" is a process rule, not a
 * statistic: it is satisfied by construction if the caller partitions {@code
 * observationsByPairAndYear} by a cohort assigned <em>before</em> this method is ever called
 * (PLAN-024 Task B's cohort rule, evaluated per OOS window), one {@link #evaluate} call per cohort
 * — never by comparing which grouping scored better here and picking that one after the fact.
 *
 * <p><strong>S3 vs. S1's own per-cell null check — deliberately not redundant.</strong> {@link
 * S1Replication} already null-tests each (pair, year) cell individually as part of qualifying it;
 * {@link #evaluate} additionally null-tests the <em>pooled</em> statistic across every observation
 * in the sample. The two answer different questions: S1 asks whether enough individually-checkable
 * cells show a real effect; S3 asks whether the effect survives at all once every observation is
 * pooled together, so a result driven entirely by a handful of cherry-picked qualifying cells
 * cannot pass on S1 alone.
 */
public final class K3Harness {

  private K3Harness() {}

  /** Frozen thresholds a K3 run is pre-registered against, before any code touches real data. */
  public record K3Thresholds(
      int nullN,
      long seed,
      int reliabilityBins,
      double maxCalibrationError,
      int minPairsReplication,
      int minYearsPerPairReplication,
      double[] reachabilityThresholds,
      double reachabilityFloorBps,
      int minObservationsPerPair) {}

  public record K3Result(
      List<S1Replication.CellResult> s1Cells,
      boolean s1Passes,
      List<ReliabilityCurve.Bin> s2ReliabilityCurve,
      BrierDecomposition.Result s2Brier,
      double s2ExpectedCalibrationError,
      boolean s2Passes,
      CircularPermutationNullModel.NullModelResult s3PooledNull,
      boolean s3Passes,
      List<EconomicReachability.ReachabilityPoint> s4Sweep,
      boolean s4Passes,
      Map<String, Integer> s6ObservationCountByPair,
      boolean s6Passes) {

    /**
     * All required gates (ADR-0028: "All six required"; S5 is process, verified outside this
     * class).
     */
    public boolean allPass() {
      return s1Passes && s2Passes && s3Passes && s4Passes && s6Passes;
    }
  }

  public static K3Result evaluate(
      Map<String, Map<Integer, List<SignalObservation>>> observationsByPairAndYear,
      K3Thresholds thresholds) {
    List<S1Replication.CellResult> cells =
        S1Replication.evaluateCells(
            observationsByPairAndYear, thresholds.nullN(), thresholds.seed());
    boolean s1 =
        S1Replication.passesReplication(
            cells, thresholds.minPairsReplication(), thresholds.minYearsPerPairReplication());

    List<SignalObservation> all = flatten(observationsByPairAndYear);
    double[] predicted = all.stream().mapToDouble(SignalObservation::probability).toArray();
    boolean[] outcomes = new boolean[all.size()];
    for (int i = 0; i < all.size(); i++) {
      outcomes[i] = all.get(i).directionCorrect();
    }
    List<ReliabilityCurve.Bin> curve =
        ReliabilityCurve.compute(predicted, outcomes, thresholds.reliabilityBins());
    double ece = ReliabilityCurve.expectedCalibrationError(curve);
    BrierDecomposition.Result brier =
        BrierDecomposition.compute(predicted, outcomes, thresholds.reliabilityBins());
    boolean s2 = ece <= thresholds.maxCalibrationError();

    double[] realized = all.stream().mapToDouble(SignalObservation::directionalReturn).toArray();
    CircularPermutationNullModel.NullModelResult pooledNull =
        predicted.length < 2
            ? new CircularPermutationNullModel.NullModelResult(0.0, new double[0], 0.0, 0, 0)
            : CircularPermutationNullModel.run(
                predicted, realized, RankIc::spearman, thresholds.nullN(), thresholds.seed());
    boolean s3 = pooledNull.beatsP95();

    List<EconomicReachability.ReachabilityPoint> sweep =
        EconomicReachability.sweep(all, thresholds.reachabilityThresholds());
    boolean s4 =
        EconomicReachability.reachable(
            sweep, thresholds.reachabilityFloorBps(), thresholds.minObservationsPerPair());

    Map<String, Integer> countByPair = new TreeMap<>();
    for (SignalObservation o : all) {
      countByPair.merge(o.pair(), 1, Integer::sum);
    }
    boolean s6 =
        !countByPair.isEmpty()
            && countByPair.values().stream()
                .allMatch(count -> count >= thresholds.minObservationsPerPair());

    return new K3Result(
        cells, s1, curve, brier, ece, s2, pooledNull, s3, sweep, s4, Map.copyOf(countByPair), s6);
  }

  private static List<SignalObservation> flatten(
      Map<String, Map<Integer, List<SignalObservation>>> observationsByPairAndYear) {
    List<SignalObservation> out = new ArrayList<>();
    for (Map<Integer, List<SignalObservation>> byYear : observationsByPairAndYear.values()) {
      for (List<SignalObservation> cell : byYear.values()) {
        out.addAll(cell);
      }
    }
    return List.copyOf(out);
  }
}
