package app.viglide.core.indicator;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.Test;

/** Unit and property tests for {@link RsiCalculator}. */
class RsiCalculatorTest {

  // ── Unit tests ───────────────────────────────────────────────────────────────────────────────

  @Test
  void offsetIsPeriod_andSingleValueForMinimumInput() {
    List<BigDecimal> prices = buildPrices(100.0, 15, +1.0); // 15 ascending prices
    IndicatorSeries result = RsiCalculator.calculate(prices, 14);
    assertThat(result.offset()).isEqualTo(14);
    assertThat(result.values()).hasSize(1);
  }

  @Test
  void rejectsTooFewPrices() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                RsiCalculator.calculate(
                    List.of(BigDecimal.ONE, BigDecimal.TWO, BigDecimal.valueOf(3)), 14));
  }

  @Test
  void rejectsPeriodZero() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> RsiCalculator.calculate(List.of(BigDecimal.ONE, BigDecimal.TWO), 0));
  }

  @Test
  void rejectsNullPrices() {
    assertThatNullPointerException().isThrownBy(() -> RsiCalculator.calculate(null, 14));
  }

  // ── Property tests ───────────────────────────────────────────────────────────────────────────

  @Property
  void strictlyIncreasing_allRsiIs100(@ForAll @IntRange(min = 2, max = 20) int period) {
    List<BigDecimal> prices = buildPrices(100.0, period + 5, +1.0);
    IndicatorSeries result = RsiCalculator.calculate(prices, period);
    for (BigDecimal rsi : result.values()) {
      assertThat(rsi).isEqualByComparingTo(BigDecimal.valueOf(100));
    }
  }

  @Property
  void strictlyDecreasing_allRsiIs0(@ForAll @IntRange(min = 2, max = 20) int period) {
    List<BigDecimal> prices = buildPrices(1000.0, period + 5, -1.0);
    IndicatorSeries result = RsiCalculator.calculate(prices, period);
    for (BigDecimal rsi : result.values()) {
      assertThat(rsi).isEqualByComparingTo(BigDecimal.ZERO);
    }
  }

  @Property
  void allRsiValuesInZeroToHundredRange(
      @ForAll @Size(min = 16, max = 50)
          List<@DoubleRange(min = 1.0, max = 1000.0) Double> rawPrices) {
    List<BigDecimal> prices = rawPrices.stream().map(BigDecimal::valueOf).toList();
    IndicatorSeries result = RsiCalculator.calculate(prices, 14);
    for (BigDecimal rsi : result.values()) {
      assertThat(rsi.compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0);
      assertThat(rsi.compareTo(BigDecimal.valueOf(100))).isLessThanOrEqualTo(0);
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

  /**
   * Builds a price series of {@code size} values starting at {@code start} with constant {@code
   * step}.
   */
  private static List<BigDecimal> buildPrices(double start, int size, double step) {
    List<BigDecimal> prices = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      prices.add(BigDecimal.valueOf(start + i * step));
    }
    return prices;
  }
}
