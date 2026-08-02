package app.viglide.core.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TargetPosition}. */
class TargetPositionTest {

  private static final List<Factor> NO_FACTORS = List.of();

  @Test
  void happyPath_positiveWeight() {
    TargetPosition tp =
        new TargetPosition(
            "BTCUSDT", new BigDecimal("0.5"), PositionShape.SPOT_ONLY, NO_FACTORS, "explanation");
    assertThat(tp.symbol()).isEqualTo("BTCUSDT");
    assertThat(tp.targetWeight()).isEqualByComparingTo("0.5");
    assertThat(tp.shape()).isEqualTo(PositionShape.SPOT_ONLY);
  }

  @Test
  void spotOnly_allowsNegativeWeight() {
    assertThatCode(
            () ->
                new TargetPosition(
                    "BTCUSDT",
                    new BigDecimal("-0.5"),
                    PositionShape.SPOT_ONLY,
                    NO_FACTORS,
                    "short"))
        .doesNotThrowAnyException();
  }

  @Test
  void weightBoundary_oneAndNegativeOneAreAllowed() {
    assertThatCode(
            () ->
                new TargetPosition(
                    "BTCUSDT", BigDecimal.ONE, PositionShape.SPOT_ONLY, NO_FACTORS, "max long"))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                new TargetPosition(
                    "BTCUSDT",
                    BigDecimal.ONE.negate(),
                    PositionShape.SPOT_ONLY,
                    NO_FACTORS,
                    "max short"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsWeightAboveOne() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TargetPosition(
                    "BTCUSDT",
                    new BigDecimal("1.01"),
                    PositionShape.SPOT_ONLY,
                    NO_FACTORS,
                    "too big"));
  }

  @Test
  void rejectsWeightBelowNegativeOne() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TargetPosition(
                    "BTCUSDT",
                    new BigDecimal("-1.01"),
                    PositionShape.SPOT_ONLY,
                    NO_FACTORS,
                    "too big"));
  }

  @Test
  void deltaNeutralCarry_rejectsNegativeWeight() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TargetPosition(
                    "BTCUSDT",
                    new BigDecimal("-0.1"),
                    PositionShape.DELTA_NEUTRAL_CARRY,
                    NO_FACTORS,
                    "must never be negative — exiting is 0, not negative"));
  }

  @Test
  void deltaNeutralCarry_allowsZeroAndPositive() {
    assertThatCode(
            () ->
                new TargetPosition(
                    "BTCUSDT",
                    BigDecimal.ZERO,
                    PositionShape.DELTA_NEUTRAL_CARRY,
                    NO_FACTORS,
                    "flat"))
        .doesNotThrowAnyException();
  }

  @Test
  void spotLong_rejectsNegativeWeight() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TargetPosition(
                    "BTCUSDT",
                    new BigDecimal("-0.1"),
                    PositionShape.SPOT_LONG,
                    NO_FACTORS,
                    "long-only"));
  }

  @Test
  void perpShort_rejectsPositiveWeight() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TargetPosition(
                    "BTCUSDT",
                    new BigDecimal("0.1"),
                    PositionShape.PERP_SHORT,
                    NO_FACTORS,
                    "short-only"));
  }

  @Test
  void perpShort_allowsZeroAndNegative() {
    assertThatCode(
            () ->
                new TargetPosition(
                    "BTCUSDT",
                    new BigDecimal("-0.3"),
                    PositionShape.PERP_SHORT,
                    NO_FACTORS,
                    "short"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsBlankSymbol() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TargetPosition(
                    " ", BigDecimal.ZERO, PositionShape.SPOT_ONLY, NO_FACTORS, "blank"));
  }

  @Test
  void rejectsBlankExplanation() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TargetPosition(
                    "BTCUSDT", BigDecimal.ZERO, PositionShape.SPOT_ONLY, NO_FACTORS, " "));
  }

  @Test
  void factorsAreDefensivelyCopied() {
    ArrayList<Factor> mutable = new ArrayList<>();
    mutable.add(new Factor("CODE", "detail", 0.5));
    TargetPosition tp =
        new TargetPosition(
            "BTCUSDT", BigDecimal.ZERO, PositionShape.SPOT_ONLY, mutable, "explanation");
    mutable.add(new Factor("CODE2", "detail2", 0.1));
    assertThat(tp.factors()).hasSize(1);
  }

  @Test
  void rejectsNullFields() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new TargetPosition(
                    null, BigDecimal.ZERO, PositionShape.SPOT_ONLY, NO_FACTORS, "x"));
    assertThatNullPointerException()
        .isThrownBy(
            () -> new TargetPosition("BTCUSDT", null, PositionShape.SPOT_ONLY, NO_FACTORS, "x"));
    assertThatNullPointerException()
        .isThrownBy(() -> new TargetPosition("BTCUSDT", BigDecimal.ZERO, null, NO_FACTORS, "x"));
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new TargetPosition("BTCUSDT", BigDecimal.ZERO, PositionShape.SPOT_ONLY, null, "x"));
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new TargetPosition(
                    "BTCUSDT", BigDecimal.ZERO, PositionShape.SPOT_ONLY, NO_FACTORS, null));
  }
}
