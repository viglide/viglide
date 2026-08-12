package app.viglide.research.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.viglide.core.params.JsonReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link K3EvaluateCli} — the run path {@code K3Harness} shipped without.
 *
 * <p>These cover the wiring the harness's own tests cannot: CSV parsing, cohort scoping, the
 * pre-registration defaults, and the override disclosure. The statistics themselves are tested in
 * {@code K3HarnessTest} and each gate's own test.
 */
class K3EvaluateCliTest {

  private static final double[] CALIBRATION_BUCKETS = {0.55, 0.65, 0.75, 0.85, 0.95};

  @Test
  void evaluatesAGoodSignalAndWritesTheExpectedJsonShape(@TempDir Path tmp) throws IOException {
    // 4 pairs x 2 years -- exactly S1's replication floor -- honestly calibrated, 150bps edge.
    Path csv = tmp.resolve("obs.csv");
    Files.writeString(
        csv,
        observationsCsv(
            List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"),
            List.of(2023, 2024),
            /* observationsPerCell= */ 300,
            /* edgeMagnitude= */ 0.015,
            /* seed= */ 1L));

    String console = run("--observations=" + csv, "--cohort=MAJOR", "--out=" + tmp);

    assertThat(console)
        .contains("K3 gate (ADR-0028) -- cohort=MAJOR")
        .contains("S1 predictive content PASS")
        .contains("S2 calibration PASS")
        .contains("S3 pooled null PASS")
        .contains("S4 reachability PASS")
        .contains("S6 per-pair floor PASS")
        .contains("VERDICT: PASS")
        .doesNotContain("[OVERRIDDEN]");

    Map<String, Object> json = readJson(tmp.resolve("k3-result-major.json"));
    assertThat(json).containsEntry("verdict", "PASS").containsEntry("cohort", "MAJOR");
    assertThat(list(json.get("overriddenFromPreRegistration"))).isEmpty();
    assertThat(list(json.get("pairs"))).containsExactly("BNBUSDT", "BTCUSDT", "ETHUSDT", "SOLUSDT");

    @SuppressWarnings("unchecked")
    Map<String, Object> thresholds = (Map<String, Object>) json.get("thresholds");
    // The defaults ARE PLAN-024 §0.1's pre-registration -- pinned so a later edit to the numbers
    // has to be a deliberate change to a documented pre-registration, not a quiet default tweak.
    assertThat(((Number) thresholds.get("nullN")).intValue()).isEqualTo(200);
    assertThat(((Number) thresholds.get("seed")).longValue()).isEqualTo(42L);
    assertThat(((Number) thresholds.get("reachabilityFloorBps")).doubleValue()).isEqualTo(60.0);
    assertThat(((Number) thresholds.get("minObservationsPerPair")).intValue()).isEqualTo(30);
    assertThat(((Number) thresholds.get("s4MinTradeCountPerPair")).intValue()).isEqualTo(30);
    assertThat(((Number) thresholds.get("s4MinQualifyingPairs")).intValue()).isEqualTo(4);

    assertThat(json).containsKeys("s1", "s2", "s3", "s4", "s5", "s6");
  }

  @Test
  void perPairCountsArePrintedInPairOrder_notMapCopyOfOrder(@TempDir Path tmp) throws IOException {
    // K3Result's per-pair maps are Map.copyOf, whose iteration order is randomized per JVM. Two
    // runs of the same data must print identically or an auditor diffing them reads reordering as
    // a change (NFR-7). Asserting sorted order rather than run-to-run equality is deliberate:
    // Map.copyOf's order is stable *within* one JVM, so comparing two in-process runs would pass
    // even with the sorting removed. Dropping the TreeMap fails this in 23 of 24 JVMs.
    Path csv = tmp.resolve("obs.csv");
    Files.writeString(
        csv,
        observationsCsv(
            List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"),
            List.of(2023, 2024),
            300,
            0.015,
            1L));

    String console = run("--observations=" + csv, "--cohort=MAJOR");

    assertThat(console)
        .contains("S6 per-pair floor PASS -- {BNBUSDT=600, BTCUSDT=600, ETHUSDT=600, SOLUSDT=600}")
        .contains("pairs={BNBUSDT=600, BTCUSDT=600, ETHUSDT=600, SOLUSDT=600}");
  }

  @Test
  void theFundingarbShapeFailsOnS4Alone(@TempDir Path tmp) throws IOException {
    // A real, honestly-calibrated signal with a 5bps edge: fundingarb's own shape. S4 is the only
    // gate that may reject it, and the CLI must report that rather than a bare failure.
    Path csv = tmp.resolve("obs.csv");
    Files.writeString(
        csv,
        observationsCsv(
            List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"),
            List.of(2023, 2024),
            300,
            /* edgeMagnitude= */ 0.0005,
            1L));

    String console = run("--observations=" + csv, "--cohort=MAJOR");

    assertThat(console)
        .contains("S1 predictive content PASS")
        .contains("S4 reachability FAIL")
        .contains("VERDICT: FAIL");
  }

  @Test
  void loweringThePreRegisteredFloorIsEffectiveAndIsDisclosed(@TempDir Path tmp)
      throws IOException {
    // The same 5bps signal passes S4 once the floor is dropped below its edge. Two things at once:
    // the override reaches the harness (an undisclosed-but-inert flag would be worse than none),
    // and taking it is announced on the console and recorded in the JSON.
    Path csv = tmp.resolve("obs.csv");
    Files.writeString(
        csv,
        observationsCsv(
            List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"),
            List.of(2023, 2024),
            300,
            0.0005,
            1L));

    String console =
        run(
            "--observations=" + csv,
            "--cohort=MAJOR",
            "--reachability-floor-bps=1.0",
            "--out=" + tmp);

    assertThat(console)
        .contains("[OVERRIDDEN]")
        .contains("reachabilityFloorBps")
        .contains("S4 reachability PASS");

    Map<String, Object> json = readJson(tmp.resolve("k3-result-major.json"));
    assertThat(list(json.get("overriddenFromPreRegistration")))
        .containsExactly("reachabilityFloorBps");
  }

  @Test
  void pairsFilterScopesTheRunToOneCohort(@TempDir Path tmp) throws IOException {
    Path csv = tmp.resolve("obs.csv");
    Files.writeString(
        csv,
        observationsCsv(
            List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"),
            List.of(2023, 2024),
            300,
            0.015,
            1L));

    String console =
        run("--observations=" + csv, "--cohort=ALT", "--pairs=SOLUSDT,BNBUSDT", "--out=" + tmp);

    assertThat(console).contains("pairs=2");
    Map<String, Object> json = readJson(tmp.resolve("k3-result-alt.json"));
    assertThat(list(json.get("pairs"))).containsExactly("BNBUSDT", "SOLUSDT");
    // Two pairs cannot clear S1's four-pair replication floor, whatever the edge looks like.
    assertThat(json).containsEntry("verdict", "FAIL");
  }

  @Test
  void cohortIsRequired_becauseS5IsNotSatisfiedByAMixedUniverse(@TempDir Path tmp)
      throws IOException {
    Path csv = tmp.resolve("obs.csv");
    Files.writeString(csv, observationsCsv(List.of("BTCUSDT"), List.of(2023), 50, 0.015, 1L));

    assertThatThrownBy(() -> run("--observations=" + csv))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--cohort is required");
  }

  @Test
  void aMalformedRowNamesTheFileAndLine(@TempDir Path tmp) throws IOException {
    Path csv = tmp.resolve("obs.csv");
    Files.writeString(
        csv,
        """
        pair,oosYear,asOf,probability,direction,forwardReturn
        BTCUSDT,2023,2023-01-01T00:00:00Z,0.7,BUY,0.01
        BTCUSDT,2023,2023-01-01T01:00:00Z,0.7,BUY
        """);

    assertThatThrownBy(() -> run("--observations=" + csv, "--cohort=MAJOR"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("obs.csv:3")
        .hasMessageContaining("expected 6 columns");
  }

  @Test
  void anOutOfRangeProbabilityIsRejectedWithItsLineNumber(@TempDir Path tmp) throws IOException {
    Path csv = tmp.resolve("obs.csv");
    Files.writeString(
        csv,
        """
        pair,oosYear,asOf,probability,direction,forwardReturn
        BTCUSDT,2023,2023-01-01T00:00:00Z,1.7,BUY,0.01
        """);

    assertThatThrownBy(() -> run("--observations=" + csv, "--cohort=MAJOR"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("obs.csv:2")
        .hasMessageContaining("probability must be in [0,1]");
  }

  /**
   * Runs the CLI and collapses runs of spaces, so assertions read the report's content rather than
   * its column alignment.
   */
  private static String run(String... argv) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    K3EvaluateCli.run(argv, new PrintStream(buf, true, StandardCharsets.UTF_8));
    return buf.toString(StandardCharsets.UTF_8).replaceAll("[ ]+", " ");
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readJson(Path path) throws IOException {
    return (Map<String, Object>) JsonReader.parse(Files.readString(path));
  }

  /**
   * Same construction as {@code K3HarnessTest.syntheticSignal} — within each stated-confidence
   * bucket, exactly that fraction of calls are made correct, so calibration is honest by
   * construction rather than by sampling luck — rendered as the CLI's own CSV format.
   */
  private static String observationsCsv(
      List<String> pairs, List<Integer> years, int observationsPerCell, double edge, long seed) {
    Random rng = new Random(seed);
    StringBuilder sb = new StringBuilder("pair,oosYear,asOf,probability,direction,forwardReturn\n");
    int perBucket = Math.max(2, observationsPerCell / CALIBRATION_BUCKETS.length);
    for (String pair : pairs) {
      for (int year : years) {
        for (double p : CALIBRATION_BUCKETS) {
          int correctCount = (int) Math.round(p * perBucket);
          List<Boolean> outcomes = new ArrayList<>();
          for (int i = 0; i < perBucket; i++) {
            outcomes.add(i < correctCount);
          }
          Collections.shuffle(outcomes, rng);
          for (boolean correct : outcomes) {
            sb.append(pair)
                .append(',')
                .append(year)
                .append(',')
                .append(String.format("%d-01-01T00:00:00Z", year))
                .append(',')
                .append(p)
                .append(",BUY,")
                .append(correct ? edge : -edge)
                .append('\n');
          }
        }
      }
    }
    return sb.toString();
  }
}
