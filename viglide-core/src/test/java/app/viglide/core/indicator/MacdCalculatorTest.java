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
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

/** Unit and property tests for {@link MacdCalculator}. */
class MacdCalculatorTest {

  // ── Anchor: constant series ⇒ MACD, signal, histogram all zero ───────────────────────────────

  @Test
  void anchor_constantSeries_allComponentsAreZero() {
    BigDecimal hundred = BigDecimal.valueOf(100);
    List<BigDecimal> prices = Collections.nCopies(40, hundred);

    MacdSeries result = MacdCalculator.calculate(prices, 12, 26, 9);

    assertThat(result.offset()).isEqualTo(33); // (26-1) + (9-1)
    assertThat(result.macd()).isNotEmpty();
    for (BigDecimal v : result.macd()) {
      assertThat(v).isEqualByComparingTo("0");
    }
    for (BigDecimal v : result.signal()) {
      assertThat(v).isEqualByComparingTo("0");
    }
    for (BigDecimal v : result.histogram()) {
      assertThat(v).isEqualByComparingTo("0");
    }
  }

  // ── Output shape and lengths ─────────────────────────────────────────────────────────────────

  @Test
  void minimumLength_producesExactlyTwoValues() {
    // 12 + 26 + 9 = 47? No: required = slow + signal = 26 + 9 = 35.
    List<BigDecimal> prices = constantPrices(35, 100);
    MacdSeries result = MacdCalculator.calculate(prices, 12, 26, 9);
    // At minimum length: MACD length = 35 - 26 + 1 = 10, signal length = 10 - 9 + 1 = 2.
    assertThat(result.macd()).hasSize(2);
    assertThat(result.signal()).hasSize(2);
    assertThat(result.histogram()).hasSize(2);
  }

  @Test
  void smallParams_produceExpectedOffset() {
    List<BigDecimal> prices = constantPrices(20, 100);
    MacdSeries result = MacdCalculator.calculate(prices, 3, 5, 3);
    assertThat(result.offset()).isEqualTo(6); // (5-1) + (3-1)
  }

  // ── Validation ───────────────────────────────────────────────────────────────────────────────

  @Test
  void rejectsNullPrices() {
    assertThatNullPointerException().isThrownBy(() -> MacdCalculator.calculate(null, 12, 26, 9));
  }

  @Test
  void rejectsSlowNotGreaterThanFast() {
    List<BigDecimal> prices = constantPrices(50, 100);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MacdCalculator.calculate(prices, 12, 12, 9));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MacdCalculator.calculate(prices, 26, 12, 9));
  }

  @Test
  void rejectsFastPeriodLessThanOne() {
    List<BigDecimal> prices = constantPrices(50, 100);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MacdCalculator.calculate(prices, 0, 26, 9));
  }

  @Test
  void rejectsSignalPeriodLessThanOne() {
    List<BigDecimal> prices = constantPrices(50, 100);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MacdCalculator.calculate(prices, 12, 26, 0));
  }

  @Test
  void rejectsTooFewPrices() {
    List<BigDecimal> prices = constantPrices(30, 100); // 30 < 26 + 9 = 35
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MacdCalculator.calculate(prices, 12, 26, 9));
  }

  // ── previous* accessors ──────────────────────────────────────────────────────────────────────

  @Test
  void previousAccessorsThrow_whenOnlyOneValue() {
    // We cannot reach a 1-value MacdSeries via MacdCalculator (it requires slow+signal ⇒ ≥ 2).
    // Construct one directly to test the guard.
    MacdSeries oneVal =
        new MacdSeries(
            0, List.of(BigDecimal.ONE), List.of(BigDecimal.ZERO), List.of(BigDecimal.ONE));
    org.assertj.core.api.Assertions.assertThatIllegalStateException()
        .isThrownBy(oneVal::previousMacd);
    org.assertj.core.api.Assertions.assertThatIllegalStateException()
        .isThrownBy(oneVal::previousSignal);
  }

  // ── Property tests ───────────────────────────────────────────────────────────────────────────

  @Property
  void histogramEqualsMacdMinusSignal_exact(
      @ForAll @Size(min = 35, max = 80)
          List<@DoubleRange(min = 1.0, max = 10000.0) Double> rawPrices) {
    List<BigDecimal> prices = rawPrices.stream().map(BigDecimal::valueOf).toList();
    MacdSeries result = MacdCalculator.calculate(prices, 12, 26, 9);
    for (int i = 0; i < result.histogram().size(); i++) {
      BigDecimal expected = result.macd().get(i).subtract(result.signal().get(i));
      // Exact BigDecimal subtraction — must match.
      assertThat(result.histogram().get(i)).isEqualByComparingTo(expected);
    }
  }

  @Property
  void monotoneIncreasingSeries_macdEventuallyPositive(
      @ForAll @DoubleRange(min = 0.1, max = 5.0) double step) {
    int size = 60;
    java.util.ArrayList<BigDecimal> prices = new java.util.ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      prices.add(BigDecimal.valueOf(100.0 + i * step));
    }
    MacdSeries result = MacdCalculator.calculate(prices, 12, 26, 9);
    // On a strict uptrend fast EMA must be above slow EMA at the last bar, so MACD > 0.
    assertThat(result.lastMacd().signum()).isPositive();
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

  private static List<BigDecimal> constantPrices(int count, double value) {
    return Collections.nCopies(count, BigDecimal.valueOf(value));
  }
}
