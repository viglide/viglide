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
 * PLAN-022 Task B: {@link PortfolioCalibrateCli} is {@link PortfolioCalibrationHarness}'s CLI entry
 * point, launched by a quotable command instead of a JUnit test class (the trap PLAN-016 Task C's
 * own smoke/overnight run exists to avoid). Uses a fixture strategy registered via {@code
 * META-INF/services} ({@link app.viglide.research.cli.fixtures.FixtureCarryPortfolioStrategy}) —
 * never a real strategy — same isolation {@link PortfolioCliTest} keeps from {@code fundingarb}.
 */
class PortfolioCalibrateCliTest {

  @Test
  void run_fitsRegisteredStrategy_writesManifestAndTop_neverTouchesWinners(@TempDir Path tmp)
      throws IOException {
    Path datasetsDir = tmp.resolve("data");
    Files.createDirectories(datasetsDir);
    Files.writeString(datasetsDir.resolve("AAA_1h_2024.csv"), alternatingCandlesCsv(60));
    Files.writeString(datasetsDir.resolve("AAA_spot_1h_2024.csv"), alternatingCandlesCsv(60));
    Files.writeString(datasetsDir.resolve("AAA_funding_2024.csv"), fundingCsv(60));

    Path outDir = tmp.resolve("out");
    Path winnersPath = tmp.resolve("winners.json"); // must never be created by this CLI

    int exitCode =
        PortfolioCalibrateCli.run(
            new String[] {
              "--pairs=AAA",
              "--strategy=fixture-carry-cli",
              "--label=2024",
              "--datasets-dir=" + datasetsDir,
              "--folds=2",
              "--embargo-bars=0",
              "--min-trades=0",
              "--warmup-bars=5",
              "--trial-registry=" + tmp.resolve("trials.jsonl"),
              "--out=" + outDir
            });

    assertThat(exitCode).isEqualTo(0);
    assertThat(Files.exists(winnersPath)).isFalse();

    @SuppressWarnings("unchecked")
    Map<String, Object> manifest =
        (Map<String, Object>) JsonReader.parse(Files.readString(outDir.resolve("manifest.json")));
    assertThat((List<String>) manifest.get("pairsIncluded")).containsExactly("AAA");
    assertThat(((Number) manifest.get("candidatesRequested")).intValue()).isEqualTo(2);
    assertThat(((Number) manifest.get("trials")).intValue()).isEqualTo(2);

    assertThat(Files.readString(outDir.resolve("top.csv"))).contains("variant");
    assertThat(Files.exists(outDir.resolve("progress.log"))).isTrue();
  }

  /**
   * The placeholder manifest is only ever read when a run dies before finishing, so its trials /
   * candidatesRequested pair is exactly what has to be right — under this CLI's own "trials &lt;
   * candidatesRequested means the sweep was cut short" rule, writing those two the wrong way round
   * would leave an aborted run's manifest claiming a completed sweep.
   */
  @Test
  void run_abortedMidSweep_leavesManifestReadingAsIncomplete_neverAsCompleted(@TempDir Path tmp)
      throws IOException {
    Path datasetsDir = tmp.resolve("data");
    Files.createDirectories(datasetsDir);
    Files.writeString(datasetsDir.resolve("AAA_1h_2024.csv"), alternatingCandlesCsv(60));
    Files.writeString(datasetsDir.resolve("AAA_funding_2024.csv"), fundingCsv(60));
    // Spot covers only the first 15 bars, so the later fold's own slice comes up empty and "AAA" is
    // (correctly, PLAN-022 Task C) not carry-capable there -- which the fixture strategy, targeting
    // DELTA_NEUTRAL_CARRY unconditionally, then trips over. Any mid-sweep abort would do; this is
    // simply the one this PR newly makes reachable.
    Files.writeString(datasetsDir.resolve("AAA_spot_1h_2024.csv"), alternatingCandlesCsv(15));

    Path outDir = tmp.resolve("out");
    String[] argv = {
      "--pairs=AAA",
      "--strategy=fixture-carry-cli",
      "--label=2024",
      "--datasets-dir=" + datasetsDir,
      "--folds=2",
      "--embargo-bars=0",
      "--min-trades=0",
      "--warmup-bars=5",
      "--trial-registry=" + tmp.resolve("trials.jsonl"),
      "--out=" + outDir
    };

    assertThatThrownBy(() -> PortfolioCalibrateCli.run(argv))
        .isInstanceOf(IllegalArgumentException.class);

    @SuppressWarnings("unchecked")
    Map<String, Object> manifest =
        (Map<String, Object>) JsonReader.parse(Files.readString(outDir.resolve("manifest.json")));
    assertThat(((Number) manifest.get("trials")).intValue()).isZero();
    assertThat(((Number) manifest.get("candidatesRequested")).intValue()).isEqualTo(2);
  }

  /**
   * PLAN-023 Task D. PLAN-022 shipped this CLI with no TrialRegistry call at all, so the 2026-08-10
   * Stage 2 carry runs spent 720 real-corpus candidate evaluations and recorded none, leaving DSR
   * deflation for the carry half un-chargeable. Nothing in the output of a run reveals that, which
   * is why it needs its own test rather than an eyeball.
   */
  @Test
  void run_appendsTrialsToTheRegistry_accumulatingAcrossRunsOnOneFingerprint(@TempDir Path tmp)
      throws IOException {
    Path datasetsDir = tmp.resolve("data");
    Files.createDirectories(datasetsDir);
    Files.writeString(datasetsDir.resolve("AAA_1h_2024.csv"), alternatingCandlesCsv(60));
    Files.writeString(datasetsDir.resolve("AAA_spot_1h_2024.csv"), alternatingCandlesCsv(60));
    Files.writeString(datasetsDir.resolve("AAA_funding_2024.csv"), fundingCsv(60));
    Path registry = tmp.resolve("trials.jsonl");
    String[] argv = {
      "--pairs=AAA",
      "--strategy=fixture-carry-cli",
      "--label=2024",
      "--datasets-dir=" + datasetsDir,
      "--folds=2",
      "--embargo-bars=0",
      "--min-trades=0",
      "--warmup-bars=5",
      "--trial-registry=" + registry,
      "--out=" + tmp.resolve("out")
    };

    PortfolioCalibrateCli.run(argv);
    PortfolioCalibrateCli.run(argv);

    List<String> lines = Files.readAllLines(registry);
    assertThat(lines).hasSize(2);
    @SuppressWarnings("unchecked")
    Map<String, Object> first = (Map<String, Object>) JsonReader.parse(lines.get(0));
    @SuppressWarnings("unchecked")
    Map<String, Object> second = (Map<String, Object>) JsonReader.parse(lines.get(1));
    // Same data, so the same fingerprint -- otherwise repeated searching would never accumulate.
    assertThat(second.get("datasetFingerprint")).isEqualTo(first.get("datasetFingerprint"));
    assertThat(((Number) first.get("candidateCount")).intValue()).isEqualTo(2);

    @SuppressWarnings("unchecked")
    Map<String, Object> manifest =
        (Map<String, Object>)
            JsonReader.parse(Files.readString(tmp.resolve("out").resolve("manifest.json")));
    assertThat(((Number) manifest.get("cumulativeTrialsForPanel")).intValue()).isEqualTo(4);
    assertThat(manifest.get("datasetFingerprint")).isEqualTo(first.get("datasetFingerprint"));
  }

  @Test
  void run_missingSpotDataset_skipsPairAndRefuses(@TempDir Path tmp) throws IOException {
    Path datasetsDir = tmp.resolve("data");
    Files.createDirectories(datasetsDir);
    // Kline and funding present, spot absent -- pair must be excluded, not silently zero-filled.
    Files.writeString(datasetsDir.resolve("AAA_1h_2024.csv"), alternatingCandlesCsv(60));
    Files.writeString(datasetsDir.resolve("AAA_funding_2024.csv"), fundingCsv(60));

    int exitCode =
        PortfolioCalibrateCli.run(
            new String[] {
              "--pairs=AAA",
              "--strategy=fixture-carry-cli",
              "--label=2024",
              "--datasets-dir=" + datasetsDir,
              "--trial-registry=" + tmp.resolve("trials.jsonl"),
              "--out=" + tmp.resolve("out")
            });

    assertThat(exitCode).isNotEqualTo(0);
  }

  private static String alternatingCandlesCsv(int bars) {
    StringBuilder sb = new StringBuilder();
    Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
    for (int i = 0; i < bars; i++) {
      long openTimeMs = t0.plusSeconds(3600L * i).toEpochMilli();
      String price = i % 2 == 0 ? "100" : "101";
      sb.append(openTimeMs)
          .append(',')
          .append(price)
          .append(',')
          .append(price)
          .append(',')
          .append(price)
          .append(',')
          .append(price)
          .append(",1000000\n");
    }
    return sb.toString();
  }

  private static String fundingCsv(int bars) {
    StringBuilder sb = new StringBuilder();
    Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
    for (int i = 0; i < bars; i += 8) {
      long timeMs = t0.plusSeconds(3600L * i).toEpochMilli();
      sb.append(timeMs).append(",0.001\n");
    }
    return sb.toString();
  }
}
