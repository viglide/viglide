package app.viglide.core.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TechnicalSignal}. */
class TechnicalSignalTest {

  private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
  private static final Factor FACTOR = new Factor("EMA", "bullish cross", 0.8);

  @Test
  void happyPath_constructsValidSignal() {
    TechnicalSignal s =
        new TechnicalSignal("BTCUSDT", Direction.BUY, 0.75, List.of(FACTOR), "BUY signal", T0);
    assertThat(s.symbol()).isEqualTo("BTCUSDT");
    assertThat(s.direction()).isEqualTo(Direction.BUY);
    assertThat(s.confidence()).isEqualTo(0.75);
    assertThat(s.factors()).containsExactly(FACTOR);
    assertThat(s.explanation()).isEqualTo("BUY signal");
    assertThat(s.asOf()).isEqualTo(T0);
  }

  @Test
  void factorsListIsDefensivelyCopied() {
    ArrayList<Factor> mutable = new ArrayList<>(List.of(FACTOR));
    TechnicalSignal s = new TechnicalSignal("BTCUSDT", Direction.HOLD, 0.0, mutable, "hold", T0);
    mutable.add(new Factor("RSI", "neutral", 0.5));
    assertThat(s.factors()).hasSize(1);
  }

  @Test
  void acceptsConfidenceBoundaryValues() {
    assertThatCode(() -> new TechnicalSignal("X", Direction.BUY, 0.0, List.of(), "e", T0))
        .doesNotThrowAnyException();
    assertThatCode(() -> new TechnicalSignal("X", Direction.SELL, 1.0, List.of(), "e", T0))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsBlankSymbol() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new TechnicalSignal("", Direction.BUY, 0.5, List.of(), "e", T0));
  }

  @Test
  void rejectsConfidenceBelowZero() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new TechnicalSignal("X", Direction.BUY, -0.1, List.of(), "e", T0));
  }

  @Test
  void rejectsConfidenceAboveOne() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new TechnicalSignal("X", Direction.BUY, 1.1, List.of(), "e", T0));
  }

  @Test
  void rejectsBlankExplanation() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new TechnicalSignal("X", Direction.HOLD, 0.5, List.of(), "  ", T0));
  }

  @Test
  void rejectsNullDirection() {
    assertThatNullPointerException()
        .isThrownBy(() -> new TechnicalSignal("X", null, 0.5, List.of(), "e", T0));
  }

  @Test
  void rejectsNullAsOf() {
    assertThatNullPointerException()
        .isThrownBy(() -> new TechnicalSignal("X", Direction.BUY, 0.5, List.of(), "e", null));
  }
}
