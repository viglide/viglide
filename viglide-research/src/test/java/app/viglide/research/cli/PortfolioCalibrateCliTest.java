package app.viglide.research.cli;

import static org.assertj.core.api.Assertions.assertThat;

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
