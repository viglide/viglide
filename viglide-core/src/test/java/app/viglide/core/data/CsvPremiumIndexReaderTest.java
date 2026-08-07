package app.viglide.core.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.viglide.core.domain.PremiumIndexEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link CsvPremiumIndexReader}. */
class CsvPremiumIndexReaderTest {

  @Test
  void parsesBinanceKlineShapedRows(@TempDir Path dir) throws IOException {
    Path csv = dir.resolve("premiumidx.csv");
    Files.writeString(
        csv,
        """
        1704067200000,0.00075030,0.00137721,0.00051408,0.00068158,0,1704070799999,0,720,0,0,0
        1704070800000,0.00062958,0.00164705,0.00050038,0.00083600,0,1704074399999,0,720,0,0,0
        """);
    try (var s = CsvPremiumIndexReader.stream(csv)) {
      List<PremiumIndexEvent> es = s.toList();
      assertThat(es).hasSize(2);
      assertThat(es.get(0).time().toEpochMilli()).isEqualTo(1704067200000L);
      // Column 4 (close) is the sample value, not column 1 (open).
      assertThat(es.get(0).value().toPlainString()).isEqualTo("0.00068158");
      assertThat(es.get(1).value().toPlainString()).isEqualTo("0.00083600");
    }
  }

  @Test
  void skipsHeaderAndBlankRows(@TempDir Path dir) throws IOException {
    Path csv = dir.resolve("merged.csv");
    Files.writeString(
        csv,
        """
        open_time,open,high,low,close,volume
        1704067200000,0.0007,0.0013,0.0005,0.0006,0

        open_time,open,high,low,close,volume
        1704070800000,0.0006,0.0016,0.0005,0.0008,0
        """);
    try (var s = CsvPremiumIndexReader.stream(csv)) {
      assertThat(s.count()).isEqualTo(2L);
    }
  }

  @Test
  void negativeValueIsPreserved(@TempDir Path dir) throws IOException {
    Path csv = dir.resolve("negative.csv");
    Files.writeString(csv, "1704067200000,-0.0001,-0.00005,-0.0002,-0.00015,0\n");
    try (var s = CsvPremiumIndexReader.stream(csv)) {
      List<PremiumIndexEvent> es = s.toList();
      assertThat(es.getFirst().value().signum()).isNegative();
    }
  }

  @Test
  void malformedValueRowFailsLoudly(@TempDir Path dir) throws IOException {
    Path csv = dir.resolve("bad.csv");
    Files.writeString(csv, "1704067200000,0.0007,0.0013,0.0005,nope,0\n");
    assertThatThrownBy(() -> CsvPremiumIndexReader.stream(csv).toList())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("malformed premium-index CSV row");
  }

  @Test
  void tooFewColumnsIsTreatedAsHeader(@TempDir Path dir) throws IOException {
    Path csv = dir.resolve("short.csv");
    Files.writeString(csv, "1704067200000,0.0007,0.0013,0.0005\n");
    try (var s = CsvPremiumIndexReader.stream(csv)) {
      assertThat(s.count()).isZero();
    }
  }
}
