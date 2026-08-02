package app.viglide.core.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

/** Unit and property tests for {@link SmaCalculator}. */
class SmaCalculatorTest {

  // ── Anchor test ──────────────────────────────────────────────────────────────────────────────

  @Test
  void anchor_sma3Of1to6_isOffset2Values234And5() {
    // SMA(3) of [1,2,3,4,5,6] = [mean(1,2,3), mean(2,3,4), mean(3,4,5), mean(4,5,6)] = [2,3,4,5]
    List<BigDecimal> prices =
        List.of(
            BigDecimal.ONE,
            BigDecimal.TWO,
            BigDecimal.valueOf(3),
            BigDecimal.valueOf(4),
            BigDecimal.valueOf(5),
            BigDecimal.valueOf(6));

    IndicatorSeries result = SmaCalculator.calculate(prices, 3);

    assertThat(result.offset()).isEqualTo(2);
    assertThat(result.values()).hasSize(4);
    assertThat(result.values().get(0)).isEqualByComparingTo("2");
    assertThat(result.values().get(1)).isEqualByComparingTo("3");
    assertThat(result.values().get(2)).isEqualByComparingTo("4");
    assertThat(result.values().get(3)).isEqualByComparingTo("5");
  }

  // ── Unit tests ───────────────────────────────────────────────────────────────────────────────

  @Test
  void period1_returnsAllPricesUnchanged() {
    List<BigDecimal> prices = List.of(BigDecimal.ONE, BigDecimal.TWO, BigDecimal.valueOf(3));
    IndicatorSeries result = SmaCalculator.calculate(prices, 1);
    assertThat(result.offset()).isEqualTo(0);
    assertThat(result.values()).hasSize(3);
    assertThat(result.values().get(0)).isEqualByComparingTo("1");
    assertThat(result.values().get(2)).isEqualByComparingTo("3");
  }

  @Test
  void exactlyPeriodPrices_returnsSingleSeedValue() {
    List<BigDecimal> prices = List.of(BigDecimal.ONE, BigDecimal.TWO, BigDecimal.valueOf(3));
    IndicatorSeries result = SmaCalculator.calculate(prices, 3);
    assertThat(result.values()).hasSize(1);
    assertThat(result.values().get(0)).isEqualByComparingTo("2"); // avg(1,2,3)
  }

  @Test
  void rejectsTooFewPrices() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SmaCalculator.calculate(List.of(BigDecimal.ONE), 2));
  }

  @Test
  void rejectsPeriodZero() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SmaCalculator.calculate(List.of(BigDecimal.ONE), 0));
  }

  @Test
  void rejectsNullPrices() {
    assertThatNullPointerException().isThrownBy(() -> SmaCalculator.calculate(null, 3));
  }

  // ── Property tests ───────────────────────────────────────────────────────────────────────────

  @Property
  void constantSeries_smaEqualsConstant(
      @ForAll @IntRange(min = 1, max = 30) int period,
      @ForAll @DoubleRange(min = 1.0, max = 1000.0) double value) {
    int size = period + 10;
    BigDecimal constant = BigDecimal.valueOf(value);
    List<BigDecimal> prices = Collections.nCopies(size, constant);

    IndicatorSeries result = SmaCalculator.calculate(prices, period);

    for (BigDecimal v : result.values()) {
      assertThat(v).isEqualByComparingTo(constant);
    }
  }

  @Property
  void smaValuesStayWithinInputRange(
      @ForAll @Size(min = 3, max = 60)
          List<@DoubleRange(min = 1.0, max = 10000.0) Double> rawPrices) {
    List<BigDecimal> prices = rawPrices.stream().map(BigDecimal::valueOf).toList();
    int period = Math.min(3, prices.size());
    BigDecimal min = prices.stream().reduce(prices.get(0), BigDecimal::min);
    BigDecimal max = prices.stream().reduce(prices.get(0), BigDecimal::max);

    IndicatorSeries result = SmaCalculator.calculate(prices, period);

    for (BigDecimal v : result.values()) {
      assertThat(v.compareTo(min)).isGreaterThanOrEqualTo(0);
      assertThat(v.compareTo(max)).isLessThanOrEqualTo(0);
    }
  }
}
