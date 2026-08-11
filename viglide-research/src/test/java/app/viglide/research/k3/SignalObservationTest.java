package app.viglide.research.k3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import app.viglide.core.domain.Direction;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SignalObservation}. */
class SignalObservationTest {

  private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");

  @Test
  void buyDirectionalReturnEqualsForwardReturn() {
    SignalObservation obs = new SignalObservation("BTCUSDT", 2024, T0, 0.7, Direction.BUY, 0.02);
    assertThat(obs.directionalReturn()).isEqualTo(0.02);
    assertThat(obs.directionCorrect()).isTrue();
  }

  @Test
  void sellDirectionalReturnIsNegatedForwardReturn() {
    SignalObservation obs = new SignalObservation("BTCUSDT", 2024, T0, 0.7, Direction.SELL, 0.02);
    assertThat(obs.directionalReturn()).isEqualTo(-0.02);
    assertThat(obs.directionCorrect()).isFalse();
  }

  @Test
  void sellCorrectWhenForwardReturnNegative() {
    SignalObservation obs = new SignalObservation("BTCUSDT", 2024, T0, 0.7, Direction.SELL, -0.02);
    assertThat(obs.directionalReturn()).isEqualTo(0.02);
    assertThat(obs.directionCorrect()).isTrue();
  }

  @Test
  void rejectsHoldDirection() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SignalObservation("BTCUSDT", 2024, T0, 0.7, Direction.HOLD, 0.0));
  }

  @Test
  void rejectsProbabilityOutOfRange() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SignalObservation("BTCUSDT", 2024, T0, 1.5, Direction.BUY, 0.0));
  }

  @Test
  void rejectsBlankPair() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SignalObservation("", 2024, T0, 0.5, Direction.BUY, 0.0));
  }

  @Test
  void rejectsNullPair() {
    assertThatNullPointerException()
        .isThrownBy(() -> new SignalObservation(null, 2024, T0, 0.5, Direction.BUY, 0.0));
  }
}
