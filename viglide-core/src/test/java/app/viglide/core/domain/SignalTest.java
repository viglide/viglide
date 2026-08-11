package app.viglide.core.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Signal}. */
class SignalTest {

  private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
  private static final Duration HORIZON = Duration.ofHours(24);

  @Test
  void happyPath_constructsValidSignal() {
    Signal s = new Signal("BTCUSDT", HORIZON, Direction.BUY, 0.7, T0);
    assertThat(s.pair()).isEqualTo("BTCUSDT");
    assertThat(s.horizon()).isEqualTo(HORIZON);
    assertThat(s.direction()).isEqualTo(Direction.BUY);
    assertThat(s.probability()).isEqualTo(0.7);
    assertThat(s.asOf()).isEqualTo(T0);
  }

  @Test
  void acceptsProbabilityBoundaryValues() {
    assertThatCode(() -> new Signal("X", HORIZON, Direction.BUY, 0.0, T0))
        .doesNotThrowAnyException();
    assertThatCode(() -> new Signal("X", HORIZON, Direction.SELL, 1.0, T0))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsBlankPair() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Signal("", HORIZON, Direction.BUY, 0.5, T0));
  }

  @Test
  void rejectsZeroHorizon() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Signal("X", Duration.ZERO, Direction.BUY, 0.5, T0));
  }

  @Test
  void rejectsNegativeHorizon() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Signal("X", Duration.ofHours(-1), Direction.BUY, 0.5, T0));
  }

  @Test
  void rejectsHoldDirection() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Signal("X", HORIZON, Direction.HOLD, 0.5, T0))
        .withMessageContaining("HOLD");
  }

  @Test
  void rejectsProbabilityBelowZero() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Signal("X", HORIZON, Direction.BUY, -0.1, T0));
  }

  @Test
  void rejectsProbabilityAboveOne() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Signal("X", HORIZON, Direction.BUY, 1.1, T0));
  }

  @Test
  void rejectsNullPair() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Signal(null, HORIZON, Direction.BUY, 0.5, T0));
  }

  @Test
  void rejectsNullHorizon() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Signal("X", null, Direction.BUY, 0.5, T0));
  }

  @Test
  void rejectsNullDirection() {
    assertThatNullPointerException().isThrownBy(() -> new Signal("X", HORIZON, null, 0.5, T0));
  }

  @Test
  void rejectsNullAsOf() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Signal("X", HORIZON, Direction.BUY, 0.5, null));
  }
}
