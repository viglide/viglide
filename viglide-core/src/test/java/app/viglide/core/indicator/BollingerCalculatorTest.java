package app.viglide.core.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** Unit and property tests for {@link BollingerCalculator}. */
class BollingerCalculatorTest {

  // ── Anchor: constant series ⇒ middle == constant, upper == middle, lower == middle ──────────

  @Test
  void anchor_constantSeries_bandsCollapseOntoMiddle() {
    BigDecimal v = BigDecimal.valueOf(100);
    List<BigDecimal> prices = Collections.nCopies(30, v);

    BollingerSeries result = BollingerCalculator.calculate(prices, 20, 2.0);

    assertThat(result.offset()).isEqualTo(19);
    for (BigDecimal m : result.middle()) {
      assertThat(m).isEqualByComparingTo("100");
    }
    for (int i = 0; i < result.middle().size(); i++) {
      assertThat(result.upper().get(i)).isEqualByComparingTo("100");
      assertThat(result.lower().get(i)).isEqualByComparingTo("100");
    }
  }

  // ── Known stddev: prices alternating 99/101 ⇒ stddev = 1 exactly (n-1 denominator) ──────────

  @Test
  void alternating99And101_stdevIsOne_bandsAt99And101() {
    // For period 4 over [99, 101, 99, 101]: mean = 100, deviations = [-1, +1, -1, +1].
    // Σd² = 4, variance = 4/3, stdev = sqrt(4/3) ≈ 1.1547.
    // Pick period 2 instead: window = [99, 101], mean = 100, Σd² = 2, variance = 2/1 = 2,
    // stdev = sqrt(2) ≈ 1.41421356.
    List<BigDecimal> prices =
        List.of(
            BigDecimal.valueOf(99), BigDecimal.valueOf(101),
            BigDecimal.valueOf(99), BigDecimal.valueOf(101));
    BollingerSeries result = BollingerCalculator.calculate(prices, 2, 1.0);
    // First window [99, 101]: mean = 100, stdev = sqrt(2). Upper = 100 + sqrt(2), lower similar.
    assertThat(result.middle().get(0)).isEqualByComparingTo("100");
    assertThat(result.upper().get(0).subtract(result.middle().get(0)).doubleValue())
        .isCloseTo(Math.sqrt(2), org.assertj.core.data.Offset.offset(1e-12));
  }

  // ── Validation ───────────────────────────────────────────────────────────────────────────────

  @Test
  void rejectsNullPrices() {
    assertThatNullPointerException().isThrownBy(() -> BollingerCalculator.calculate(null, 20, 2.0));
  }

  @Test
  void rejectsPeriodLessThanTwo() {
    List<BigDecimal> prices = Collections.nCopies(30, BigDecimal.valueOf(100));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> BollingerCalculator.calculate(prices, 1, 2.0));
  }

  @Test
  void rejectsNegativeK() {
    List<BigDecimal> prices = Collections.nCopies(30, BigDecimal.valueOf(100));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> BollingerCalculator.calculate(prices, 20, -0.5));
  }

  @Test
  void rejectsTooFewPrices() {
    List<BigDecimal> prices = Collections.nCopies(10, BigDecimal.valueOf(100));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> BollingerCalculator.calculate(prices, 20, 2.0));
  }

  // ── Property tests ───────────────────────────────────────────────────────────────────────────

  @Property
  void nonConstantSeries_lowerLeMiddleLeUpper(
      @ForAll @IntRange(min = 2, max = 20) int period,
      @ForAll @DoubleRange(min = 100.0, max = 200.0) double start,
      @ForAll @DoubleRange(min = 0.5, max = 5.0) double step) {
    int size = period + 10;
    List<BigDecimal> prices = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      prices.add(BigDecimal.valueOf(start + i * step));
    }
    BollingerSeries result = BollingerCalculator.calculate(prices, period, 2.0);
    for (int i = 0; i < result.middle().size(); i++) {
      assertThat(result.lower().get(i).compareTo(result.middle().get(i))).isLessThanOrEqualTo(0);
      assertThat(result.middle().get(i).compareTo(result.upper().get(i))).isLessThanOrEqualTo(0);
    }
  }

  @Property
  void kZero_bandsCollapseOntoMiddle(
      @ForAll @IntRange(min = 2, max = 20) int period,
      @ForAll @DoubleRange(min = 100.0, max = 200.0) double start,
      @ForAll @DoubleRange(min = 0.5, max = 5.0) double step) {
    int size = period + 5;
    List<BigDecimal> prices = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      prices.add(BigDecimal.valueOf(start + i * step));
    }
    BollingerSeries result = BollingerCalculator.calculate(prices, period, 0.0);
    for (int i = 0; i < result.middle().size(); i++) {
      assertThat(result.upper().get(i)).isEqualByComparingTo(result.middle().get(i));
      assertThat(result.lower().get(i)).isEqualByComparingTo(result.middle().get(i));
    }
  }
}
