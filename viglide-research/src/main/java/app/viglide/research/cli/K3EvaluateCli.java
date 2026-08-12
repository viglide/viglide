package app.viglide.research.cli;

import app.viglide.core.domain.Direction;
import app.viglide.core.params.JsonWriter;
import app.viglide.research.k3.BrierDecomposition;
import app.viglide.research.k3.CircularPermutationNullModel;
import app.viglide.research.k3.EconomicReachability;
import app.viglide.research.k3.K3Harness;
import app.viglide.research.k3.ReliabilityCurve;
import app.viglide.research.k3.S1Replication;
import app.viglide.research.k3.SignalObservation;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs the K3 signal gate (ADR-0028) against a file of realized {@link SignalObservation}s and
 * reports S1–S6 with the number that decided each one.
 *
 * <p>Invoke as: {@code ./gradlew :viglide-research:k3Evaluate --args='--observations=obs.csv
 * --cohort=ALT --out=docs/notes'}
 *
 * <p><strong>Why this exists.</strong> {@code K3Harness} shipped with no caller outside its own
 * test (PLAN-024 Task D; the plan closed at Task E, before the entry points Task F would have
 * built). A harness that can only be reached from a test is the exact defect PLAN-023 was written
 * to fix — {@code PanelCalibrationHarness} was called "the highest-value item in the plan" and was
 * unreachable for three plans running. This class is the run path, built while the finding was
 * fresh rather than left for a future session to rediscover.
 *
 * <p><strong>The defaults are the pre-registration.</strong> Every threshold defaults to the value
 * PLAN-024 §0.1 froze before any hypothesis touched data. Overriding one is legitimate — a future
 * gate may pre-register different numbers — but it is never silent: an overridden threshold is
 * named on the console and recorded in {@code k3-result.json} under {@code
 * overriddenFromPreRegistration}, so a report cannot quietly be produced against friendlier numbers
 * than the ones it claims.
 *
 * <p><strong>{@code --cohort} is required, and that is S5.</strong> ADR-0028 S5 requires cohorts to
 * be declared ex ante and evaluated one at a time — a single run over a mixed universe is not a
 * cohort result. The flag is mandatory so the cohort under test is a stated input recorded in the
 * output, not an implicit property of whichever pairs happened to be in the file.
 *
 * <p><strong>A FAIL is a result, not an error.</strong> This CLI exits 0 whatever the verdict; only
 * malformed input is an error. Reporting a negative is the deliverable (ADR-0026).
 */
public final class K3EvaluateCli {

  private K3EvaluateCli() {}

  /** PLAN-024 §0.1, frozen 2026-08-11 before any hypothesis touched data. */
  static final K3Harness.K3Thresholds PRE_REGISTERED =
      new K3Harness.K3Thresholds(
          /* nullN= */ 200,
          /* seed= */ 42L,
          /* reliabilityBins= */ 10,
          /* maxCalibrationError= */ 0.10,
          /* minPairsReplication= */ 4,
          /* minYearsPerPairReplication= */ 2,
          /* reachabilityThresholds= */ new double[] {0.5, 0.6, 0.7, 0.8, 0.9},
          /* reachabilityFloorBps= */ 60.0,
          /* minObservationsPerPair= */ 30,
          /* s4MinTradeCountPerPair= */ 30,
          /* s4MinQualifyingPairs= */ 4);

  private static final String CSV_COLUMNS = "pair,oosYear,asOf,probability,direction,forwardReturn";

  public static void main(String[] argv) throws IOException {
    run(argv, System.out);
  }

  public static void run(String[] argv, PrintStream out) throws IOException {
    Map<String, String> args = Args.parse(argv);
    Path observationsPath = Paths.get(Args.require(args, "observations"));
    String cohort = Args.require(args, "cohort");
    Set<String> pairFilter =
        args.containsKey("pairs") ? Set.of(Args.require(args, "pairs").split(",")) : Set.of();

    K3Harness.K3Thresholds thresholds = thresholdsFrom(args);
    List<String> overridden = overriddenFrom(thresholds);

    List<SignalObservation> observations = readObservations(observationsPath, pairFilter);
    if (observations.isEmpty()) {
      throw new IllegalStateException(
          "no observations read from " + observationsPath + " (after --pairs filtering)");
    }
    Map<String, Map<Integer, List<SignalObservation>>> byPairYear =
        groupByPairAndYear(observations);

    K3Harness.K3Result result = K3Harness.evaluate(byPairYear, thresholds);

    report(out, cohort, observationsPath, observations.size(), byPairYear, overridden, result);

    String outArg = args.get("out");
    if (outArg != null && !outArg.isBlank()) {
      Path outDir = Paths.get(outArg);
      Files.createDirectories(outDir);
      Path target = outDir.resolve("k3-result-" + cohort.toLowerCase(Locale.ROOT) + ".json");
      Files.writeString(
          target,
          JsonWriter.pretty(
              json(cohort, observationsPath, byPairYear, thresholds, overridden, result)));
      out.println("wrote " + target);
    }
  }

  private static K3Harness.K3Thresholds thresholdsFrom(Map<String, String> args) {
    String sweep = Args.opt(args, "reachability-thresholds", "");
    double[] reachabilityThresholds =
        sweep.isBlank()
            ? PRE_REGISTERED.reachabilityThresholds().clone()
            : java.util.Arrays.stream(sweep.split(",")).mapToDouble(Double::parseDouble).toArray();
    return new K3Harness.K3Thresholds(
        Args.intOpt(args, "null-n", PRE_REGISTERED.nullN()),
        Args.longOpt(args, "seed", PRE_REGISTERED.seed()),
        Args.intOpt(args, "reliability-bins", PRE_REGISTERED.reliabilityBins()),
        Args.doubleOpt(args, "max-calibration-error", PRE_REGISTERED.maxCalibrationError()),
        Args.intOpt(args, "min-pairs-replication", PRE_REGISTERED.minPairsReplication()),
        Args.intOpt(
            args, "min-years-per-pair-replication", PRE_REGISTERED.minYearsPerPairReplication()),
        reachabilityThresholds,
        Args.doubleOpt(args, "reachability-floor-bps", PRE_REGISTERED.reachabilityFloorBps()),
        Args.intOpt(args, "min-observations-per-pair", PRE_REGISTERED.minObservationsPerPair()),
        Args.intOpt(args, "s4-min-trade-count-per-pair", PRE_REGISTERED.s4MinTradeCountPerPair()),
        Args.intOpt(args, "s4-min-qualifying-pairs", PRE_REGISTERED.s4MinQualifyingPairs()));
  }

  /**
   * Names every threshold that differs from PLAN-024 §0.1's frozen value. Reported rather than
   * refused: a later gate may legitimately pre-register different numbers, but a run against
   * friendlier ones must say so in its own output.
   */
  static List<String> overriddenFrom(K3Harness.K3Thresholds t) {
    List<String> out = new ArrayList<>();
    if (t.nullN() != PRE_REGISTERED.nullN()) out.add("nullN");
    if (t.seed() != PRE_REGISTERED.seed()) out.add("seed");
    if (t.reliabilityBins() != PRE_REGISTERED.reliabilityBins()) out.add("reliabilityBins");
    if (t.maxCalibrationError() != PRE_REGISTERED.maxCalibrationError()) {
      out.add("maxCalibrationError");
    }
    if (t.minPairsReplication() != PRE_REGISTERED.minPairsReplication()) {
      out.add("minPairsReplication");
    }
    if (t.minYearsPerPairReplication() != PRE_REGISTERED.minYearsPerPairReplication()) {
      out.add("minYearsPerPairReplication");
    }
    if (!java.util.Arrays.equals(
        t.reachabilityThresholds(), PRE_REGISTERED.reachabilityThresholds())) {
      out.add("reachabilityThresholds");
    }
    if (t.reachabilityFloorBps() != PRE_REGISTERED.reachabilityFloorBps()) {
      out.add("reachabilityFloorBps");
    }
    if (t.minObservationsPerPair() != PRE_REGISTERED.minObservationsPerPair()) {
      out.add("minObservationsPerPair");
    }
    if (t.s4MinTradeCountPerPair() != PRE_REGISTERED.s4MinTradeCountPerPair()) {
      out.add("s4MinTradeCountPerPair");
    }
    if (t.s4MinQualifyingPairs() != PRE_REGISTERED.s4MinQualifyingPairs()) {
      out.add("s4MinQualifyingPairs");
    }
    return List.copyOf(out);
  }

  /**
   * Reads the observation CSV. Header line optional; columns are fixed and positional ({@code
   * pair,oosYear,asOf,probability,direction,forwardReturn}) because a K3 input file is
   * machine-written by whatever produced the signal, not hand-edited.
   */
  static List<SignalObservation> readObservations(Path path, Set<String> pairFilter)
      throws IOException {
    List<SignalObservation> out = new ArrayList<>();
    List<String> lines = Files.readAllLines(path);
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).trim();
      if (line.isEmpty() || line.startsWith("#")) continue;
      // No pair is literally named "pair", so this is an unambiguous header test at any position.
      if (line.toLowerCase(Locale.ROOT).startsWith("pair,")) continue;
      String[] f = line.split(",");
      if (f.length != 6) {
        throw new IllegalArgumentException(
            path
                + ":"
                + (i + 1)
                + ": expected 6 columns ("
                + CSV_COLUMNS
                + "), got "
                + f.length
                + ": "
                + line);
      }
      String pair = f[0].trim();
      if (!pairFilter.isEmpty() && !pairFilter.contains(pair)) continue;
      try {
        out.add(
            new SignalObservation(
                pair,
                Integer.parseInt(f[1].trim()),
                Instant.parse(f[2].trim()),
                Double.parseDouble(f[3].trim()),
                Direction.valueOf(f[4].trim().toUpperCase(Locale.ROOT)),
                Double.parseDouble(f[5].trim())));
      } catch (RuntimeException e) {
        throw new IllegalArgumentException(
            path + ":" + (i + 1) + ": " + e.getMessage() + " -- in: " + line, e);
      }
    }
    return List.copyOf(out);
  }

  static Map<String, Map<Integer, List<SignalObservation>>> groupByPairAndYear(
      List<SignalObservation> observations) {
    Map<String, Map<Integer, List<SignalObservation>>> out = new TreeMap<>();
    for (SignalObservation o : observations) {
      out.computeIfAbsent(o.pair(), p -> new TreeMap<>())
          .computeIfAbsent(o.oosYear(), y -> new ArrayList<>())
          .add(o);
    }
    return out;
  }

  private static void report(
      PrintStream out,
      String cohort,
      Path observationsPath,
      int observationCount,
      Map<String, Map<Integer, List<SignalObservation>>> byPairYear,
      List<String> overridden,
      K3Harness.K3Result r) {
    out.println("K3 gate (ADR-0028) -- cohort=" + cohort + " source=" + observationsPath);
    out.println(
        "  observations="
            + observationCount
            + " pairs="
            + byPairYear.size()
            + " cells="
            + byPairYear.values().stream().mapToInt(Map::size).sum());
    if (!overridden.isEmpty()) {
      out.println(
          "  [OVERRIDDEN] not run against PLAN-024 §0.1's pre-registered thresholds: "
              + String.join(", ", overridden));
    }

    long qualifyingCells = r.s1Cells().stream().filter(S1Replication.CellResult::qualifies).count();
    out.println(
        gate("S1 predictive content", r.s1Passes())
            + " -- "
            + qualifyingCells
            + " of "
            + r.s1Cells().size()
            + " (pair, year) cells qualify");
    out.println(
        gate("S2 calibration", r.s2Passes())
            + " -- ECE="
            + fmt(r.s2ExpectedCalibrationError())
            + " brier="
            + fmt(r.s2Brier().brierScore())
            + " reliability="
            + fmt(r.s2Brier().reliability()));
    out.println(
        gate("S3 pooled null", r.s3Passes())
            + " -- percentile="
            + fmt(r.s3PooledNull().percentile())
            + " realIc="
            + fmt(r.s3PooledNull().realStatistic()));
    out.println(gate("S4 reachability", r.s4Passes()) + " -- sweep:");
    for (EconomicReachability.ReachabilityPoint p : r.s4Sweep()) {
      out.println(
          "        p>="
              + fmt(p.probabilityThreshold())
              + "  n="
              + p.tradeCount()
              + "  edge="
              + fmt(p.edgeBps())
              + "bps  pairs="
              + new TreeMap<>(p.tradeCountByPair()));
    }
    // TreeMap, here and in the sweep above: K3Result's maps are Map.copyOf, whose iteration order
    // is deliberately randomized per JVM run. Two identical runs must print identically or an
    // auditor diffing them reads reordering as a change (NFR-7).
    out.println(
        gate("S6 per-pair floor", r.s6Passes())
            + " -- "
            + new TreeMap<>(r.s6ObservationCountByPair()));
    out.println(
        "  S5 (cohorts ex ante) is a process rule, not computed here: satisfied by running this"
            + " CLI once per cohort assigned before the run (PLAN-024 Task B).");
    out.println("VERDICT: " + (r.allPass() ? "PASS -- all of S1-S4, S6" : "FAIL"));
  }

  private static String gate(String name, boolean passes) {
    return String.format(Locale.ROOT, "  %-22s %s", name, passes ? "PASS" : "FAIL");
  }

  private static String fmt(double v) {
    return String.format(Locale.ROOT, "%.4f", v);
  }

  private static Map<String, Object> json(
      String cohort,
      Path observationsPath,
      Map<String, Map<Integer, List<SignalObservation>>> byPairYear,
      K3Harness.K3Thresholds t,
      List<String> overridden,
      K3Harness.K3Result r) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("gate", "K3 (ADR-0028)");
    json.put("cohort", cohort);
    json.put("source", observationsPath.toString());
    json.put("pairs", List.copyOf(byPairYear.keySet()));

    Map<String, Object> thresholds = new LinkedHashMap<>();
    thresholds.put("nullN", t.nullN());
    thresholds.put("seed", t.seed());
    thresholds.put("reliabilityBins", t.reliabilityBins());
    thresholds.put("maxCalibrationError", t.maxCalibrationError());
    thresholds.put("minPairsReplication", t.minPairsReplication());
    thresholds.put("minYearsPerPairReplication", t.minYearsPerPairReplication());
    thresholds.put(
        "reachabilityThresholds",
        java.util.Arrays.stream(t.reachabilityThresholds()).boxed().toList());
    thresholds.put("reachabilityFloorBps", t.reachabilityFloorBps());
    thresholds.put("minObservationsPerPair", t.minObservationsPerPair());
    thresholds.put("s4MinTradeCountPerPair", t.s4MinTradeCountPerPair());
    thresholds.put("s4MinQualifyingPairs", t.s4MinQualifyingPairs());
    json.put("thresholds", thresholds);
    json.put("overriddenFromPreRegistration", overridden);

    List<Map<String, Object>> cells = new ArrayList<>();
    for (S1Replication.CellResult c : r.s1Cells()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("pair", c.pair());
      row.put("year", c.year());
      row.put("ic", c.ic());
      row.put("nullPercentile", c.nullPercentile());
      row.put("observationCount", c.observationCount());
      row.put("qualifies", c.qualifies());
      cells.add(row);
    }
    json.put("s1", Map.of("cells", cells, "passes", r.s1Passes()));

    List<Map<String, Object>> curve = new ArrayList<>();
    for (ReliabilityCurve.Bin b : r.s2ReliabilityCurve()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("lowerBound", b.lowerBound());
      row.put("upperBound", b.upperBound());
      row.put("meanPredicted", b.meanPredicted());
      row.put("observedFrequency", b.observedFrequency());
      row.put("count", b.count());
      curve.add(row);
    }
    BrierDecomposition.Result brier = r.s2Brier();
    Map<String, Object> s2 = new LinkedHashMap<>();
    s2.put("reliabilityCurve", curve);
    s2.put("expectedCalibrationError", r.s2ExpectedCalibrationError());
    s2.put(
        "brier",
        Map.of(
            "score", brier.brierScore(),
            "reliability", brier.reliability(),
            "resolution", brier.resolution(),
            "uncertainty", brier.uncertainty()));
    s2.put("passes", r.s2Passes());
    json.put("s2", s2);

    CircularPermutationNullModel.NullModelResult nul = r.s3PooledNull();
    Map<String, Object> s3 = new LinkedHashMap<>();
    s3.put("realStatistic", nul.realStatistic());
    s3.put("percentile", nul.percentile());
    s3.put("countStrictlyBelow", nul.countStrictlyBelow());
    s3.put("countEqual", nul.countEqual());
    s3.put("passes", r.s3Passes());
    json.put("s3", s3);

    List<Map<String, Object>> sweep = new ArrayList<>();
    for (EconomicReachability.ReachabilityPoint p : r.s4Sweep()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("probabilityThreshold", p.probabilityThreshold());
      row.put("tradeCount", p.tradeCount());
      row.put("edgeBps", p.edgeBps());
      row.put("tradeCountByPair", new TreeMap<>(p.tradeCountByPair()));
      row.put("qualifyingPairs", p.pairsWithAtLeast(t.s4MinTradeCountPerPair()));
      sweep.add(row);
    }
    json.put("s4", Map.of("sweep", sweep, "passes", r.s4Passes()));

    json.put(
        "s5",
        "process rule -- satisfied by one invocation per ex-ante cohort, not computed (ADR-0028)");
    json.put(
        "s6",
        Map.of(
            "observationCountByPair",
            new TreeMap<>(r.s6ObservationCountByPair()),
            "passes",
            r.s6Passes()));
    json.put("verdict", r.allPass() ? "PASS" : "FAIL");
    return json;
  }
}
