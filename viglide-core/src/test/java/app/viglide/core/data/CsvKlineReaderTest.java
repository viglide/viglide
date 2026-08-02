package app.viglide.core.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.viglide.core.domain.Candle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link CsvKlineReader}. Cover both Binance epoch-ms and ISO-8601 timestamp
 * formats, header-row tolerance, and malformed-row failure.
 */
class CsvKlineReaderTest {

  @Test
  void parsesBinanceEpochMsRows(@TempDir Path dir) throws IOException {
    Path csv = dir.resolve("binance.csv");
    Files.writeString(
        csv,
        """
        1748736000000,104544.60,104599.60,104544.60,104599.50,72.711
        1748736060000,104599.50,104650.00,104590.00,104620.00,55.5
        """);
    try (var stream = CsvKlineReader.stream(csv)) {
      List<Candle> candles = stream.toList();
      assertThat(candles).hasSize(2);
      assertThat(candles.get(0).openTime().toEpochMilli()).isEqualTo(1748736000000L);
      assertThat(candles.get(0).close().toPlainString()).isEqualTo("104599.50");
    }
  }

  @Test
  void parsesBinanceEpochMicrosecondRows(@TempDir Path dir) throws IOException {
    // PLAN-008 Task F: Binance spot klines use microsecond epoch timestamps (1000x the millisecond
    // value um/futures klines use) — discovered when perp+spot joins in FundingArbHarnessV2
    // silently
    // matched zero bars because every spot openTime parsed ~1000 years in the future.
    Path csv = dir.resolve("spot.csv");
    Files.writeString(
        csv,
        """
        1748736000000000,104544.60,104599.60,104544.60,104599.50,72.711
        1748736060000000,104599.50,104650.00,104590.00,104620.00,55.5
        """);
    try (var stream = CsvKlineReader.stream(csv)) {
      List<Candle> candles = stream.toList();
      assertThat(candles).hasSize(2);
      assertThat(candles.get(0).openTime().toEpochMilli()).isEqualTo(1748736000000L);
      assertThat(candles.get(1).openTime().toEpochMilli()).isEqualTo(1748736060000L);
    }
  }

  @Test
  void microsecondAndMillisecondRows_joinOnTheSameOpenTime(@TempDir Path dir) throws IOException {
    // The actual failure mode: a perp (ms) candle and a spot (µs) candle for the same hour must
    // parse to the identical Instant so a time-keyed join between them matches.
    Path perpCsv = dir.resolve("perp.csv");
    Path spotCsv = dir.resolve("spot.csv");
    Files.writeString(perpCsv, "1748736000000,100,101,99,100,10\n");
    Files.writeString(spotCsv, "1748736000000000,100,101,99,100,10\n");
    try (var perp = CsvKlineReader.stream(perpCsv);
        var spot = CsvKlineReader.stream(spotCsv)) {
      assertThat(perp.toList().get(0).openTime()).isEqualTo(spot.toList().get(0).openTime());
    }
  }

  @Test
  void parsesIsoTimestampRows(@TempDir Path dir) throws IOException {
    Path csv = dir.resolve("iso.csv");
    Files.writeString(
        csv,
        """
        timestamp,open,high,low,close,volume
        2024-01-01T00:00:00Z,100.00,100.10,99.90,100.00,1000
        2024-01-01T01:00:00Z,100.00,101.10,99.90,101.00,1000
        """);
    try (var stream = CsvKlineReader.stream(csv)) {
      List<Candle> candles = stream.toList();
      assertThat(candles).hasSize(2);
      assertThat(candles.get(1).openTime().toString()).isEqualTo("2024-01-01T01:00:00Z");
    }
  }

  @Test
  void skipsHeaderRowsAndBlankLines(@TempDir Path dir) throws IOException {
    // Mirrors the bug we saw in the merged Binance dump: an ISO header at the top,
    // plus per-month Binance headers interleaved with the data.
    Path csv = dir.resolve("merged.csv");
    Files.writeString(
        csv,
        """
        timestamp,open,high,low,close,volume
        1748736000000,100,101,99,100,10

        open_time,open,high,low,close,volume
        1748736060000,100,102,98,101,20
        """);
    try (var stream = CsvKlineReader.stream(csv)) {
      List<Candle> candles = stream.toList();
      assertThat(candles).hasSize(2);
    }
  }

  @Test
  void shortRowsAreSkipped(@TempDir Path dir) throws IOException {
    Path csv = dir.resolve("short.csv");
    Files.writeString(
        csv,
        """
        1748736000000,100,101,99,100,10
        1748736060000,100,102
        1748736120000,100,103,99,102,15
        """);
    try (var stream = CsvKlineReader.stream(csv)) {
      List<Candle> candles = stream.toList();
      assertThat(candles).hasSize(2);
    }
  }

  @Test
  void malformedNumericRowFailsLoudly(@TempDir Path dir) throws IOException {
    Path csv = dir.resolve("bad.csv");
    Files.writeString(csv, "1748736000000,not_a_number,1,1,1,1\n");
    assertThatThrownBy(() -> CsvKlineReader.stream(csv).toList())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("malformed CSV row");
  }

  @Test
  void streamMustClose_butWorksUnderTryWithResources(@TempDir Path dir) throws IOException {
    Path csv = dir.resolve("close.csv");
    Files.writeString(csv, "1748736000000,1,1,1,1,1\n");
    try (var s = CsvKlineReader.stream(csv)) {
      assertThat(s.count()).isEqualTo(1L);
    }
  }
}
