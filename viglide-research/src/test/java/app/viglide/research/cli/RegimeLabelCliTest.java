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
 * Unit test for {@link RegimeLabelCli} (PLAN-011 Task I, finding F9). {@link
 * app.viglide.core.regime.RegimeLabeler} itself is already thoroughly unit-tested
 * (RegimeLabelerTest) — this test only proves the CLI's I/O wiring: it reads the kline/funding
 * CSVs, calls the labeler, and writes the expected JSON shape. Deterministic: same input files
 * always produce byte-identical output.
 */
class RegimeLabelCliTest {

  private static final Instant T0 = Instant.parse("2023-01-01T00:00:00Z");

  @Test
  void labelsEveryMonthPresent_andWritesExpectedJsonShape(@TempDir Path tmp) throws IOException {
    // Three full calendar months of hourly candles (~720/month) is enough trailing history for
    // every month to clear MIN_CANDLES_FOR_LABEL and MIN_FUNDING_EVENTS_FOR_LABEL.
    Path klinePath = tmp.resolve("BTCUSDT_1h_full.csv");
    Files.writeString(klinePath, threeMonthsOfHourlyCandlesCsv());
    Path fundingPath = tmp.resolve("BTCUSDT_funding_full.csv");
    Files.writeString(fundingPath, threeMonthsOfFundingCsv());
    Path outPath = tmp.resolve("regime.json");

    int exitCode =
        RegimeLabelCli.run(
            new String[] {
              "--symbol=BTCUSDT",
              "--dataset=" + klinePath,
              "--funding-dataset=" + fundingPath,
              "--out=" + outPath
            });

    assertThat(exitCode).isEqualTo(0);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows =
        (List<Map<String, Object>>) JsonReader.parse(Files.readString(outPath));
    assertThat(rows).hasSize(3);
    assertThat(rows.get(0).get("symbol")).isEqualTo("BTCUSDT");
    assertThat(rows.get(0).get("month")).isEqualTo("2023-01");
    assertThat(rows.get(1).get("month")).isEqualTo("2023-02");
    assertThat(rows.get(2).get("month")).isEqualTo("2023-03");
    for (Map<String, Object> row : rows) {
      assertThat(row).containsKeys("asOf", "fundingRegime", "volatilityRegime");
      assertThat((String) row.get("fundingRegime")).isIn("RICH", "COMPRESSED", "UNKNOWN");
      assertThat((String) row.get("volatilityRegime")).isIn("LOW", "MEDIUM", "HIGH", "UNKNOWN");
    }
  }

  @Test
  void deterministic_sameInputsProduceByteIdenticalOutput(@TempDir Path tmp) throws IOException {
    Path klinePath = tmp.resolve("BTCUSDT_1h_full.csv");
    Files.writeString(klinePath, threeMonthsOfHourlyCandlesCsv());
    Path fundingPath = tmp.resolve("BTCUSDT_funding_full.csv");
    Files.writeString(fundingPath, threeMonthsOfFundingCsv());
    Path out1 = tmp.resolve("regime1.json");
    Path out2 = tmp.resolve("regime2.json");

    RegimeLabelCli.run(
        new String[] {
          "--symbol=BTCUSDT",
          "--dataset=" + klinePath,
          "--funding-dataset=" + fundingPath,
          "--out=" + out1
        });
    RegimeLabelCli.run(
        new String[] {
          "--symbol=BTCUSDT",
          "--dataset=" + klinePath,
          "--funding-dataset=" + fundingPath,
          "--out=" + out2
        });

    assertThat(Files.readString(out1)).isEqualTo(Files.readString(out2));
  }

  // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

  /** Three calendar months (Jan/Feb/Mar 2023), one hourly candle at a time, mildly oscillating. */
  private static String threeMonthsOfHourlyCandlesCsv() {
    StringBuilder sb = new StringBuilder();
    Instant t = T0;
    Instant end = Instant.parse("2023-04-01T00:00:00Z");
    double price = 100.0;
    int i = 0;
    while (t.isBefore(end)) {
      double delta = (i % 24 < 12 ? 1 : -1) * 0.5;
      double open = price;
      double close = price + delta;
      double hi = Math.max(open, close) + 0.2;
      double lo = Math.min(open, close) - 0.2;
      sb.append(t.toEpochMilli())
          .append(',')
          .append(open)
          .append(',')
          .append(hi)
          .append(',')
          .append(lo)
          .append(',')
          .append(close)
          .append(",1000\n");
      price = close;
      t = t.plusSeconds(3600);
      i++;
    }
    return sb.toString();
  }

  /** A handful of funding events per month (every 8h, Binance's real cadence). */
  private static String threeMonthsOfFundingCsv() {
    StringBuilder sb = new StringBuilder();
    Instant t = T0;
    Instant end = Instant.parse("2023-04-01T00:00:00Z");
    BigDecimal rate = new BigDecimal("0.0001");
    while (t.isBefore(end)) {
      sb.append(t.toEpochMilli()).append(",8,").append(rate.toPlainString()).append('\n');
      t = t.plusSeconds(8 * 3600);
    }
    return sb.toString();
  }
}
