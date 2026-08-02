package app.viglide.research.cli;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.params.JsonReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PLAN-010 Task A4 (consumer audit): {@link PortfolioCli} must exclude a pair with no matching
 * {@code winners.json} entry, never zero-fill or silently default it into the run.
 */
class PortfolioCliTest {

  @Test
  void pairWithNoWinnersEntry_isExcludedNotZeroFilled(@TempDir Path tmp) throws IOException {
    Path datasetsDir = tmp.resolve("data");
    Files.createDirectories(datasetsDir);
    // Only PAIRA has a dataset -- PAIRB is expected to be skipped at the winners-lookup stage,
    // before the harness ever looks for a dataset file at all (verifies lookup order too).
    Files.writeString(datasetsDir.resolve("PAIRA_1h_2024.csv"), flatCandlesCsv(300));

    Path winnersPath = tmp.resolve("winners.json");
    Files.writeString(
        winnersPath,
        """
        {
          "PAIRA_emarsi_2023": {
            "params": {"emaFast": 9, "emaSlow": 21, "rsiPeriod": 14,
                        "rsiOverbought": 70.0, "rsiOversold": 30.0, "spreadScale": 0.01}
          }
        }
        """);

    Path outDir = tmp.resolve("out");
    int exitCode =
        PortfolioCli.run(
            new String[] {
              "--pairs=PAIRA,PAIRB",
              "--strategy=emarsi",
              "--label=2024",
              "--datasets-dir=" + datasetsDir,
              "--winners=" + winnersPath,
              "--warmup-bars=100",
              "--out=" + outDir
            });

    assertThat(exitCode).isEqualTo(0);
    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>) JsonReader.parse(Files.readString(outDir.resolve("result.json")));
    assertThat((List<String>) result.get("pairsIncluded")).containsExactly("PAIRA");
    assertThat((List<String>) result.get("pairsSkipped")).containsExactly("PAIRB");
  }

  @Test
  void everyPairMissingWinnersEntry_refusesRatherThanRunningWithZeroPairs(@TempDir Path tmp)
      throws IOException {
    Path datasetsDir = tmp.resolve("data");
    Files.createDirectories(datasetsDir);
    Path winnersPath = tmp.resolve("winners.json"); // never written -- stays absent

    int exitCode =
        PortfolioCli.run(
            new String[] {
              "--pairs=PAIRA,PAIRB",
              "--strategy=emarsi",
              "--label=2024",
              "--datasets-dir=" + datasetsDir,
              "--winners=" + winnersPath,
              "--out=" + tmp.resolve("out")
            });

    assertThat(exitCode).isNotEqualTo(0);
  }

  // Note: the --funding-model=v2 tests that exercised --strategy=fundingarb moved to
  // PortfolioCliFundingArbTest (viglide-strategies, private repo, PLAN-018 R-5) because fundingarb
  // is no longer on viglide-research's test classpath after the public/private split.

  private static String flatCandlesCsv(int bars) {
    StringBuilder sb = new StringBuilder();
    Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
    BigDecimal price = new BigDecimal("100");
    for (int i = 0; i < bars; i++) {
      long openTimeMs = t0.plusSeconds(3600L * i).toEpochMilli();
      sb.append(openTimeMs)
          .append(',')
          .append(price)
          .append(',')
          .append(price)
          .append(',')
          .append(price)
          .append(',')
          .append(price)
          .append(",1000\n");
    }
    return sb.toString();
  }
}
