package app.viglide.core.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MutableClock}. */
class MutableClockTest {

  @Test
  void instant_returnsInitialValueUntilSet() {
    Instant initial = Instant.parse("2026-01-01T00:00:00Z");
    MutableClock clock = new MutableClock(initial, ZoneOffset.UTC);
    assertThat(clock.instant()).isEqualTo(initial);
  }

  @Test
  void set_advancesInstant() {
    MutableClock clock = new MutableClock(Instant.EPOCH, ZoneOffset.UTC);
    Instant later = Instant.parse("2026-06-01T12:00:00Z");
    clock.set(later);
    assertThat(clock.instant()).isEqualTo(later);
  }

  @Test
  void getZone_returnsConfiguredZone() {
    MutableClock clock = new MutableClock(Instant.EPOCH, ZoneOffset.UTC);
    assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
  }

  @Test
  void withZone_returnsNewClockWithSameInstantAndNewZone() {
    Instant now = Instant.parse("2026-03-01T00:00:00Z");
    MutableClock clock = new MutableClock(now, ZoneOffset.UTC);
    ZoneId tokyo = ZoneId.of("Asia/Tokyo");

    var rezoned = clock.withZone(tokyo);

    assertThat(rezoned.getZone()).isEqualTo(tokyo);
    assertThat(rezoned.instant()).isEqualTo(now);
  }

  @Test
  void constructor_rejectsNullArguments() {
    assertThatNullPointerException().isThrownBy(() -> new MutableClock(null, ZoneOffset.UTC));
    assertThatNullPointerException().isThrownBy(() -> new MutableClock(Instant.EPOCH, null));
  }

  @Test
  void set_rejectsNull() {
    MutableClock clock = new MutableClock(Instant.EPOCH, ZoneOffset.UTC);
    assertThatNullPointerException().isThrownBy(() -> clock.set(null));
  }
}
