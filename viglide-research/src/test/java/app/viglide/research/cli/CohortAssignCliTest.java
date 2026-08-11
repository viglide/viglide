package app.viglide.research.cli;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.params.JsonReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit test for {@link CohortAssignCli} — proves the I/O wiring (reads spot klines per pair per
 * year label, writes the expected JSON shape); {@link app.viglide.research.cohort.CohortAssigner}
 * itself is thoroughly unit-tested separately (CohortAssignerTest).
 */
class CohortAssignCliTest {

  private static final Instant YEAR_2023_START = Instant.parse("2023-01-01T00:00:00Z");
  private static final Instant YEAR_2024_START = Instant.parse("2024-01-01T00:00:00Z");

  @Test
  void assignsMajorsPerYearAndWritesExpectedJsonShape(@TempDir Path tmp) throws IOException {
    // BTC has 10x ETH's volume in both years -- an unambiguous top-1.
    Files.writeString(
        tmp.resolve("BTCUSDT_spot_1h_2023.csv"), hourlyCandlesCsv(YEAR_2023_START, 40, 1_000_000));
    Files.writeString(
        tmp.resolve("ETHUSDT_spot_1h_2023.csv"), hourlyCandlesCsv(YEAR_2023_START, 40, 100_000));
    Files.writeString(
        tmp.resolve("BTCUSDT_spot_1h_2024.csv"), hourlyCandlesCsv(YEAR_2024_START, 40, 1_000_000));
    Files.writeString(
        tmp.resolve("ETHUSDT_spot_1h_2024.csv"), hourlyCandlesCsv(YEAR_2024_START, 40, 100_000));

    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    CohortAssignCli.run(
        new String[] {
          "--pairs=BTCUSDT,ETHUSDT",
          "--years=2023,2024",
          "--datasets-dir=" + tmp,
          "--interval=ONE_HOUR",
          "--lookback-days=400",
          "--top-k-majors=1",
          "--out=" + tmp
        },
        new PrintStream(buf, true, StandardCharsets.UTF_8));

    @SuppressWarnings("unchecked")
    Map<String, Object> json =
        (Map<String, Object>)
            JsonReader.parse(Files.readString(tmp.resolve("cohort-assignment.json")));
    assertThat((List<String>) json.get("pairsIncluded")).containsExactly("BTCUSDT", "ETHUSDT");

    @SuppressWarnings("unchecked")
    Map<String, Object> byWindow = (Map<String, Object>) json.get("assignmentsByWindow");
    // 2023's window has no lookback history (corpus starts exactly at 2023-01-01) so both pairs
    // read ADV=0; 2024's window has a full year of 2023 data to look back on and separates them.
    assertThat(byWindow).containsKey(YEAR_2024_START.toString());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows2024 =
        (List<Map<String, Object>>) byWindow.get(YEAR_2024_START.toString());
    Map<String, Object> btcRow =
        rows2024.stream().filter(r -> "BTCUSDT".equals(r.get("pair"))).findFirst().orElseThrow();
    assertThat(btcRow.get("cohort")).isEqualTo("MAJOR");
    assertThat(((Number) btcRow.get("advRank")).intValue()).isEqualTo(1);
  }

  @Test
  void skipsPairsWithNoSpotDataset(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("BTCUSDT_spot_1h_2023.csv"), hourlyCandlesCsv(YEAR_2023_START, 40, 1_000_000));

    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    CohortAssignCli.run(
        new String[] {
          "--pairs=BTCUSDT,MISSINGUSDT",
          "--years=2023",
          "--datasets-dir=" + tmp,
          "--interval=ONE_HOUR",
          "--lookback-days=400",
          "--top-k-majors=1"
        },
        new PrintStream(buf, true, StandardCharsets.UTF_8));

    assertThat(buf.toString(StandardCharsets.UTF_8))
        .contains("[SKIP] no spot datasets found for MISSINGUSDT");
  }

  private static String hourlyCandlesCsv(Instant start, int hours, long dollarVolumePerHour) {
    StringBuilder sb = new StringBuilder();
    Instant t = start;
    for (int i = 0; i < hours; i++) {
      // close=1 so close*volume == dollarVolumePerHour exactly.
      sb.append(t.toEpochMilli()).append(",1,1,1,1,").append(dollarVolumePerHour).append('\n');
      t = t.plusSeconds(3600);
    }
    return sb.toString();
  }
}
