package app.viglide.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CsvTimestamps}, the parser shared by every CSV reader in this package. */
class CsvTimestampsTest {

  @Test
  void parsesEpochMilliseconds() {
    assertThat(CsvTimestamps.parseOrNull("1704067200000"))
        .isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
  }

  @Test
  void parsesEpochMicrosecondsToTheSameInstant() {
    // Binance spot switched to µs while futures/um stayed in ms — the same moment either way.
    assertThat(CsvTimestamps.parseOrNull("1704067200000000"))
        .isEqualTo(CsvTimestamps.parseOrNull("1704067200000"));
  }

  @Test
  void parsesIso8601() {
    assertThat(CsvTimestamps.parseOrNull("2024-01-01T00:00:00Z"))
        .isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
  }

  @Test
  void returnsNullForHeaderBlankAndNull() {
    assertThat(CsvTimestamps.parseOrNull("open_time")).isNull();
    assertThat(CsvTimestamps.parseOrNull("timestamp")).isNull();
    assertThat(CsvTimestamps.parseOrNull("")).isNull();
    assertThat(CsvTimestamps.parseOrNull(null)).isNull();
  }

  @Test
  void returnsNullForAnAllDigitsValueTooLargeForALong() {
    assertThat(CsvTimestamps.parseOrNull("99999999999999999999999")).isNull();
  }

  @Test
  void theMillisecondMicrosecondBoundaryIsResolvedByMagnitude() {
    // Just under 10^14 is read as ms; 10^14 and above is read as µs.
    assertThat(CsvTimestamps.parseOrNull("99999999999999").toEpochMilli())
        .isEqualTo(99_999_999_999_999L);
    assertThat(CsvTimestamps.parseOrNull("100000000000000").toEpochMilli())
        .isEqualTo(100_000_000_000L);
  }
}
