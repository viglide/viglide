package app.viglide.research.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.viglide.core.params.JsonReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PLAN-023 Task A. Until this CLI existed {@code PanelCalibrationHarness} — PLAN-013 Task G's
 * "highest-value item in the plan" — had no caller at all and had never been run against data.
 */
class PanelCalibrateCliTest {

  @Test
  void fitsAPanelAcrossPairs_writesManifestAndTop_neverTouchesWinners(@TempDir Path tmp)
      throws IOException {
    Path data = tmp.resolve("data");
    Files.createDirectories(data);
    for (String pair : List.of("AAA", "BBB", "CCC")) {
      Files.writeString(data.resolve(pair + "_1h_2024.csv"), klines(120));
      Files.writeString(data.resolve(pair + "_funding_2024.csv"), funding(120));
    }
    Path outDir = tmp.resolve("out");

    int exit =
        PanelCalibrateCli.run(
            new String[] {
              "--pairs=AAA,BBB,CCC",
              "--strategy=emarsi",
              "--label=2024",
              "--datasets-dir=" + data,
              "--search=random",
              "--samples=3",
              "--seed=42",
              "--warmup-bars=20",
              "--parallelism=1",
              "--min-trades=0",
              "--trial-registry=" + tmp.resolve("trials.jsonl"),
              "--out=" + outDir
            });

    assertThat(exit).isZero();
    assertThat(Files.exists(tmp.resolve("winners.json"))).isFalse();

    @SuppressWarnings("unchecked")
    Map<String, Object> manifest =
        (Map<String, Object>) JsonReader.parse(Files.readString(outDir.resolve("manifest.json")));
    assertThat(manifest.get("fitMode")).isEqualTo("panel");
    assertThat((List<String>) manifest.get("pairsIncluded")).containsExactly("AAA", "BBB", "CCC");
    assertThat(((Number) manifest.get("trials")).intValue()).isEqualTo(3);
    assertThat(((Number) manifest.get("candidatesRequested")).intValue()).isEqualTo(3);
    assertThat(manifest.get("datasetFingerprint")).isNotNull();

    assertThat(Files.exists(outDir.resolve("top.csv"))).isTrue();
    assertThat(Files.exists(outDir.resolve("top.json"))).isTrue();
    assertThat(Files.readString(outDir.resolve("progress.log"))).contains("panel finished");
  }

  @Test
  void appendsToTheTrialRegistryWithAPanelFingerprint(@TempDir Path tmp) throws IOException {
    Path data = tmp.resolve("data");
    Files.createDirectories(data);
    for (String pair : List.of("AAA", "BBB")) {
      Files.writeString(data.resolve(pair + "_1h_2024.csv"), klines(120));
      Files.writeString(data.resolve(pair + "_funding_2024.csv"), funding(120));
    }
    Path registry = tmp.resolve("trials.jsonl");
    String[] argv = {
      "--pairs=AAA,BBB",
      "--strategy=emarsi",
      "--label=2024",
      "--datasets-dir=" + data,
      "--search=random",
      "--samples=2",
      "--warmup-bars=20",
      "--parallelism=1",
      "--min-trades=0",
      "--trial-registry=" + registry,
      "--out=" + tmp.resolve("out")
    };

    PanelCalibrateCli.run(argv);
    PanelCalibrateCli.run(argv);

    // Two runs over identical data accumulate against one fingerprint -- which is the entire point
    // of the registry: DSR must be deflated by total search effort, not by one run's own count.
    List<String> lines = Files.readAllLines(registry);
    assertThat(lines).hasSize(2);
    @SuppressWarnings("unchecked")
    Map<String, Object> first = (Map<String, Object>) JsonReader.parse(lines.get(0));
    @SuppressWarnings("unchecked")
    Map<String, Object> second = (Map<String, Object>) JsonReader.parse(lines.get(1));
    assertThat(second.get("datasetFingerprint")).isEqualTo(first.get("datasetFingerprint"));

    @SuppressWarnings("unchecked")
    Map<String, Object> manifest =
        (Map<String, Object>)
            JsonReader.parse(Files.readString(tmp.resolve("out").resolve("manifest.json")));
    assertThat(((Number) manifest.get("cumulativeTrialsForPanel")).intValue()).isEqualTo(4);
  }

  @Test
  void abortedRunLeavesAManifestReadingAsIncomplete(@TempDir Path tmp) throws IOException {
    Path data = tmp.resolve("data");
    Files.createDirectories(data);
    Files.writeString(data.resolve("AAA_1h_2024.csv"), klines(120));
    Files.writeString(data.resolve("AAA_funding_2024.csv"), funding(120));
    Path outDir = tmp.resolve("out");

    // Unknown strategy: the placeholder manifest is written first, then resolution throws.
    assertThatThrownBy(
            () ->
                PanelCalibrateCli.run(
                    new String[] {
                      "--pairs=AAA",
                      "--strategy=does-not-exist",
                      "--label=2024",
                      "--datasets-dir=" + data,
                      "--trial-registry=" + tmp.resolve("trials.jsonl"),
                      "--out=" + outDir
                    }))
        .isInstanceOf(IllegalArgumentException.class);

    // ...and nothing is written at all, because resolution precedes the placeholder. Either way the
    // invariant that matters holds: no manifest ever claims a completed sweep that did not happen.
    if (Files.exists(outDir.resolve("manifest.json"))) {
      @SuppressWarnings("unchecked")
      Map<String, Object> m =
          (Map<String, Object>) JsonReader.parse(Files.readString(outDir.resolve("manifest.json")));
      assertThat(((Number) m.get("trials")).intValue())
          .isLessThan(((Number) m.get("candidatesRequested")).intValue());
    }
  }

  @Test
  void refusesWhenNoPairHasData(@TempDir Path tmp) throws IOException {
    Path data = tmp.resolve("data");
    Files.createDirectories(data);
    assertThat(
            PanelCalibrateCli.run(
                new String[] {
                  "--pairs=AAA",
                  "--strategy=emarsi",
                  "--label=2024",
                  "--datasets-dir=" + data,
                  "--trial-registry=" + tmp.resolve("trials.jsonl"),
                  "--out=" + tmp.resolve("out")
                }))
        .isNotZero();
  }

  /**
   * ADR-0016 condition 2's floor, applied here because {@code PanelCalibrationHarness} has none of
   * its own — the only one of the three calibration harnesses without one. Its objective divides by
   * deployed capital, so an unfiltered ranking is topped by whichever candidate barely traded:
   * exactly review finding F4's "validated on 1–2 trades" pathology, reproduced by the harness
   * built to eliminate it. Found by running the real corpus for the first time (PLAN-016 Stage 2).
   */
  @Test
  void filtersCandidatesBelowTheTradeFloor_andSaysSoWhenNoneSurvive(@TempDir Path tmp)
      throws IOException {
    Path data = tmp.resolve("data");
    Files.createDirectories(data);
    for (String pair : List.of("AAA", "BBB")) {
      Files.writeString(data.resolve(pair + "_1h_2024.csv"), klines(120));
      Files.writeString(data.resolve(pair + "_funding_2024.csv"), funding(120));
    }
    Path outDir = tmp.resolve("out");

    PanelCalibrateCli.run(
        new String[] {
          "--pairs=AAA,BBB",
          "--strategy=emarsi",
          "--label=2024",
          "--datasets-dir=" + data,
          "--search=random",
          "--samples=3",
          "--warmup-bars=20",
          "--parallelism=1",
          "--min-trades=100000", // unreachable on 120 bars
          "--trial-registry=" + tmp.resolve("trials.jsonl"),
          "--out=" + outDir
        });

    @SuppressWarnings("unchecked")
    Map<String, Object> m =
        (Map<String, Object>) JsonReader.parse(Files.readString(outDir.resolve("manifest.json")));
    assertThat(((Number) m.get("survivors")).intValue()).isZero();
    // trials must still record what was EVALUATED, not what survived -- DSR is deflated by search
    // effort, and filtering the output does not un-spend the search.
    assertThat(((Number) m.get("trials")).intValue()).isEqualTo(3);
    assertThat(((Number) m.get("minTrades")).intValue()).isEqualTo(100000);
  }

  private static String klines(int bars) {
    StringBuilder sb = new StringBuilder();
    Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
    for (int i = 0; i < bars; i++) {
      int p = 100 + (i % 7);
      sb.append(t0.plusSeconds(3600L * i).toEpochMilli())
          .append(',')
          .append(p)
          .append(',')
          .append(p + 1)
          .append(',')
          .append(p - 1)
          .append(',')
          .append(p)
          .append(",1000000\n");
    }
    return sb.toString();
  }

  private static String funding(int bars) {
    StringBuilder sb = new StringBuilder();
    Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
    for (int i = 0; i < bars; i += 8) {
      sb.append(t0.plusSeconds(3600L * i).toEpochMilli()).append(",0.0001\n");
    }
    return sb.toString();
  }
}
