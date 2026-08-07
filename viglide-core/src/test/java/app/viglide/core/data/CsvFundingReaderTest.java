package app.viglide.core.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.viglide.core.domain.FundingEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link CsvFundingReader}. */
class CsvFundingReaderTest {

  @Test
  void parsesBinance3ColRows(@TempDir Path dir) throws IOException {
    Path csv = dir.resolve("funding.csv");
    Files.writeString(
        csv,
        """
        1748764800000,8,0.00010000
        1748793600000,8,-0.00005000
        """);
    try (var s = CsvFundingReader.stream(csv)) {
      List<FundingEvent> es = s.toList();
      assertThat(es).hasSize(2);
      assertThat(es.get(0).time().toEpochMilli()).isEqualTo(1748764800000L);
      assertThat(es.get(0).rate().toPlainString()).isEqualTo("0.00010000");
      assertThat(es.get(1).rate().signum()).isNegative();
    }
  }

  @Test
  void parses2ColIsoRows(@TempDir Path dir) throws IOException {
    Path csv = dir.resolve("iso.csv");
    Files.writeString(
        csv,
        """
        timestamp,rate
        2025-01-01T00:00:00Z,0.0001
        2025-01-01T08:00:00Z,0.00012
        """);
    try (var s = CsvFundingReader.stream(csv)) {
      List<FundingEvent> es = s.toList();
      assertThat(es).hasSize(2);
      assertThat(es.get(1).time().toString()).isEqualTo("2025-01-01T08:00:00Z");
    }
  }

  @Test
  void skipsHeaderAndBlankRows(@TempDir Path dir) throws IOException {
    Path csv = dir.resolve("merged.csv");
    Files.writeString(
        csv,
        """
        timestamp,interval,rate
        1748764800000,8,0.0001

        calc_time,funding_interval_hours,last_funding_rate
        1748793600000,8,0.00012
        """);
    try (var s = CsvFundingReader.stream(csv)) {
      assertThat(s.count()).isEqualTo(2L);
    }
  }

  @Test
  void malformedRateRowFailsLoudly(@TempDir Path dir) throws IOException {
    Path csv = dir.resolve("bad.csv");
    Files.writeString(csv, "1748764800000,8,nope\n");
    assertThatThrownBy(() -> CsvFundingReader.stream(csv).toList())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("malformed funding CSV row");
  }

  @Test
  void microsecondEpochParsesToTheSameInstantAsMilliseconds(@TempDir Path dir) throws IOException {
    // This reader used to carry its own ms-only timestamp parser while CsvKlineReader handled µs
    // too. A µs-format funding dump therefore parsed to a year-56000 timestamp, sorted after every
    // candle, and silently accrued no funding at all. Both readers now share CsvTimestamps.
    Path micros = dir.resolve("micros.csv");
    Files.writeString(micros, "1748764800000000,8,0.0001\n");
    Path millis = dir.resolve("millis.csv");
    Files.writeString(millis, "1748764800000,8,0.0001\n");
    try (var us = CsvFundingReader.stream(micros);
        var ms = CsvFundingReader.stream(millis)) {
      List<FundingEvent> fromMicros = us.toList();
      List<FundingEvent> fromMillis = ms.toList();
      assertThat(fromMicros).hasSize(1);
      assertThat(fromMicros.getFirst().time()).isEqualTo(fromMillis.getFirst().time());
    }
  }
}
