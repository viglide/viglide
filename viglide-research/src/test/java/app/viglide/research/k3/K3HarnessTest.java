package app.viglide.research.k3;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.domain.Direction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link K3Harness} — the deliverable ADR-0028 warns most about is a gate
 * that cannot fail, so every scenario here proves the harness actually rejects the case it is meant
 * to reject, not just that it accepts a friendly synthetic signal.
 */
class K3HarnessTest {

  private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");

  private static final K3Harness.K3Thresholds DEFAULT_THRESHOLDS =
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

  @Test
  void wellCalibratedInformativeSignalWithReachableEdgePassesEveryGate() {
    // 4 pairs, 2 years each -- exactly S1's replication floor -- with a synthetic signal generator
    // whose stated probability is honestly calibrated (p fraction of BUY calls are actually
    // correct) and whose edge (150 bps/trade) comfortably clears the 60bps floor.
    Map<String, Map<Integer, List<SignalObservation>>> byPairYear =
        syntheticSignal(
            List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"),
            List.of(2023, 2024),
            /* observationsPerCell= */ 300,
            /* edgeMagnitude= */ 0.015, // 150 bps directional move when the call is "correct"
            /* seed= */ 1L);

    K3Harness.K3Result result = K3Harness.evaluate(byPairYear, DEFAULT_THRESHOLDS);

    assertThat(result.s1Passes()).as("S1 replication").isTrue();
    assertThat(result.s2Passes())
        .as("S2 calibration, ECE=%s", result.s2ExpectedCalibrationError())
        .isTrue();
    assertThat(result.s3Passes()).as("S3 pooled null").isTrue();
    assertThat(result.s4Passes()).as("S4 reachability").isTrue();
    assertThat(result.s6Passes()).as("S6 per-pair floor").isTrue();
    assertThat(result.allPass()).isTrue();
  }

  @Test
  void realButUneconomicSignal_failsOnlyS4_theFundingarbShape() {
    // Same honestly-calibrated, statistically-real signal as above, but with an edge of 5 bps per
    // correct call -- real, but five times smaller than the 60bps floor, exactly fundingarb's own
    // shape (ADR-0027: measured breakeven 10-12bps, cheapest real execution 18.6bps). S1/S2/S3/S6
    // must still pass since the *statistical* relationship is identical; only S4 must fail.
    Map<String, Map<Integer, List<SignalObservation>>> byPairYear =
        syntheticSignal(
            List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"),
            List.of(2023, 2024),
            /* observationsPerCell= */ 300,
            /* edgeMagnitude= */ 0.0005, // 5 bps -- real, but far under the 60bps floor
            /* seed= */ 1L);

    K3Harness.K3Result result = K3Harness.evaluate(byPairYear, DEFAULT_THRESHOLDS);

    assertThat(result.s1Passes()).as("S1 replication").isTrue();
    assertThat(result.s3Passes()).as("S3 pooled null").isTrue();
    assertThat(result.s6Passes()).as("S6 per-pair floor").isTrue();
    assertThat(result.s4Passes())
        .as("S4 reachability -- must fail, edge is under the floor")
        .isFalse();
    assertThat(result.allPass())
        .as("overall verdict must be false: S4 is the falsifiability gate")
        .isFalse();
  }

  @Test
  void pureNoiseSignal_failsS1AndS3_andHasHighCalibrationError() {
    Random rng = new Random(2);
    Map<String, Map<Integer, List<SignalObservation>>> byPairYear = new HashMap<>();
    for (String pair : List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT")) {
      Map<Integer, List<SignalObservation>> byYear = new HashMap<>();
      for (int year : List.of(2023, 2024)) {
        List<SignalObservation> cell = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
          // Stated confidence is unrelated to outcome, and always claims high confidence -- both
          // uninformative (S1/S3) and overconfident (S2 -- true hit rate is ~50%, not the ~90%
          // claimed).
          double p = 0.85 + rng.nextDouble() * 0.1;
          double ret = (rng.nextDouble() - 0.5) * 0.02;
          Direction dir = rng.nextBoolean() ? Direction.BUY : Direction.SELL;
          cell.add(new SignalObservation(pair, year, T0, p, dir, ret));
        }
        byYear.put(year, cell);
      }
      byPairYear.put(pair, byYear);
    }

    K3Harness.K3Result result = K3Harness.evaluate(byPairYear, DEFAULT_THRESHOLDS);

    assertThat(result.s1Passes()).as("S1 replication on pure noise").isFalse();
    assertThat(result.s3Passes()).as("S3 pooled null on pure noise").isFalse();
    assertThat(result.s2ExpectedCalibrationError())
        .as("claims ~90% confidence at a ~50% hit rate, so ECE should be ~0.4")
        .isGreaterThan(0.30);
    assertThat(result.s2Passes()).as("S2 calibration on an overconfident signal").isFalse();
    assertThat(result.allPass()).isFalse();
  }

  @Test
  void tooFewObservationsForAPair_failsS6EvenIfOtherwiseInformative() {
    // S6 pools an individual pair's observations *across years* (ADR-0027 D1 forbids pooling
    // across the pair universe, not across a single pair's own OOS window) -- so both of
    // BTCUSDT's years must be thinned for its total to fall under the 30-observation floor.
    Map<String, Map<Integer, List<SignalObservation>>> byPairYear =
        new HashMap<>(
            syntheticSignal(
                List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"),
                List.of(2023, 2024),
                300,
                0.015,
                1L));
    Map<Integer, List<SignalObservation>> btcYears = new HashMap<>(byPairYear.get("BTCUSDT"));
    btcYears.put(2023, btcYears.get(2023).subList(0, 5));
    btcYears.put(2024, btcYears.get(2024).subList(0, 5));
    byPairYear.put("BTCUSDT", btcYears);

    K3Harness.K3Result result = K3Harness.evaluate(byPairYear, DEFAULT_THRESHOLDS);

    assertThat(result.s6Passes()).isFalse();
    assertThat(result.allPass()).isFalse();
  }

  @Test
  void s4PairBreadthFloorIsNotInert_changingItChangesTheVerdict() {
    // ADR-0027 D2: a frozen parameter must be proven to change behaviour before it is frozen. The
    // otherwise-passing signal spans 4 pairs; asking S4 for 8 must flip it, or the knob is
    // decorative and S4's breadth requirement is not really being enforced.
    Map<String, Map<Integer, List<SignalObservation>>> byPairYear =
        syntheticSignal(
            List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"),
            List.of(2023, 2024),
            300,
            0.015,
            1L);

    assertThat(K3Harness.evaluate(byPairYear, DEFAULT_THRESHOLDS).s4Passes()).isTrue();
    assertThat(K3Harness.evaluate(byPairYear, withS4QualifyingPairs(8)).s4Passes())
        .as("only 4 pairs exist, so a breadth floor of 8 must fail S4")
        .isFalse();
  }

  private static K3Harness.K3Thresholds withS4QualifyingPairs(int qualifyingPairs) {
    K3Harness.K3Thresholds t = DEFAULT_THRESHOLDS;
    return new K3Harness.K3Thresholds(
        t.nullN(),
        t.seed(),
        t.reliabilityBins(),
        t.maxCalibrationError(),
        t.minPairsReplication(),
        t.minYearsPerPairReplication(),
        t.reachabilityThresholds(),
        t.reachabilityFloorBps(),
        t.minObservationsPerPair(),
        t.s4MinTradeCountPerPair(),
        qualifyingPairs);
  }

  private static final double[] CALIBRATION_BUCKETS = {0.55, 0.65, 0.75, 0.85, 0.95};

  /**
   * Builds a signal whose stated probability is honestly calibrated: within each of five stated-
   * confidence buckets, <em>exactly</em> that bucket's fraction of calls are made directionally
   * correct (return magnitude {@code edgeMagnitude}), the rest wrong by the same magnitude in
   * reverse -- P(correct | stated p) == p by construction, deterministically rather than by
   * Bernoulli sampling, so the test is not at the mercy of a single seed's sampling noise for a
   * property (honest calibration + a real, monotonic rank relationship) the construction already
   * guarantees exactly. {@code seed} only shuffles which individual observations land in the
   * "correct" bucket, never whether the aggregate rate matches {@code p}.
   */
  private static Map<String, Map<Integer, List<SignalObservation>>> syntheticSignal(
      List<String> pairs,
      List<Integer> years,
      int observationsPerCell,
      double edgeMagnitude,
      long seed) {
    Random rng = new Random(seed);
    Map<String, Map<Integer, List<SignalObservation>>> byPairYear = new HashMap<>();
    int perBucket = Math.max(2, observationsPerCell / CALIBRATION_BUCKETS.length);
    for (String pair : pairs) {
      Map<Integer, List<SignalObservation>> byYear = new HashMap<>();
      for (int year : years) {
        List<SignalObservation> cell = new ArrayList<>();
        for (double p : CALIBRATION_BUCKETS) {
          int correctCount = (int) Math.round(p * perBucket);
          List<Boolean> outcomes = new ArrayList<>();
          for (int i = 0; i < perBucket; i++) {
            outcomes.add(i < correctCount);
          }
          java.util.Collections.shuffle(outcomes, rng);
          for (boolean correct : outcomes) {
            double ret = correct ? edgeMagnitude : -edgeMagnitude;
            cell.add(new SignalObservation(pair, year, T0, p, Direction.BUY, ret));
          }
        }
        byYear.put(year, cell);
      }
      byPairYear.put(pair, byYear);
    }
    return byPairYear;
  }
}
