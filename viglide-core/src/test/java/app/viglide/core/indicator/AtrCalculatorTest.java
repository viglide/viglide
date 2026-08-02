package app.viglide.core.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import app.viglide.core.domain.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** Unit and property tests for {@link AtrCalculator}. */
class AtrCalculatorTest {

  // ── Anchor: constant-range candles with no gaps ⇒ ATR equals the range exactly ───────────────

  @Test
  void anchor_constantRangeNoGaps_atrEqualsRange() {
    // Build 20 candles where each bar has high-low=10 and close=open (no gap to next bar).
    // True range collapses to (high - low) = 10 on every bar, so ATR(14) = 10 exactly.
    List<Candle> candles = constantRange(20, 100.0, 10.0);
    IndicatorSeries result = AtrCalculator.calculate(candles, 14);

    assertThat(result.offset()).isEqualTo(14);
    assertThat(result.values()).hasSize(20 - 14); // trCount=19, signal length=19-14+1=6
    for (BigDecimal v : result.values()) {
      assertThat(v).isEqualByComparingTo("10");
    }
  }

  // ── Constant series (zero variance) ⇒ ATR is zero ───────────────────────────────────────────

  @Test
  void constantOhlcv_atrIsZero() {
    List<Candle> candles = new ArrayList<>();
    Instant t = Instant.parse("2024-01-01T00:00:00Z");
    BigDecimal v = BigDecimal.valueOf(100);
    for (int i = 0; i < 20; i++) {
      candles.add(new Candle(t.plusSeconds(i), v, v, v, v, BigDecimal.ONE));
    }
    IndicatorSeries result = AtrCalculator.calculate(candles, 14);
    for (BigDecimal a : result.values()) {
      assertThat(a).isEqualByComparingTo("0");
    }
  }

  // ── Validation ───────────────────────────────────────────────────────────────────────────────

  @Test
  void rejectsNullCandles() {
    assertThatNullPointerException().isThrownBy(() -> AtrCalculator.calculate(null, 14));
  }

  @Test
  void rejectsPeriodZero() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> AtrCalculator.calculate(constantRange(2, 100, 1), 0));
  }

  @Test
  void rejectsTooFewCandles() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> AtrCalculator.calculate(constantRange(14, 100, 1), 14));
  }

  // ── Property tests ───────────────────────────────────────────────────────────────────────────

  @Property
  void atrIsAlwaysNonNegative(
      @ForAll @IntRange(min = 2, max = 20) int period,
      @ForAll @DoubleRange(min = 100.0, max = 1000.0) double startPrice,
      @ForAll @DoubleRange(min = 0.0, max = 50.0) double range) {
    // startPrice >= 100, range <= 50 ⇒ low = startPrice - range/2 ≥ 75, always > 0.
    List<Candle> candles = constantRange(period + 5, startPrice, range);
    IndicatorSeries result = AtrCalculator.calculate(candles, period);
    for (BigDecimal v : result.values()) {
      assertThat(v.signum()).isGreaterThanOrEqualTo(0);
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

  /**
   * Builds {@code count} candles where every bar has the same close, and a constant {@code range} =
   * high − low. Adjacent bars share a close so true range = (high − low) on every bar after the
   * first.
   */
  private static List<Candle> constantRange(int count, double close, double range) {
    List<Candle> out = new ArrayList<>(count);
    Instant t = Instant.parse("2024-01-01T00:00:00Z");
    BigDecimal c = BigDecimal.valueOf(close);
    BigDecimal half = BigDecimal.valueOf(range / 2.0);
    BigDecimal high = c.add(half);
    BigDecimal low = c.subtract(half);
    for (int i = 0; i < count; i++) {
      out.add(new Candle(t.plusSeconds(i * 3600L), c, high, low, c, BigDecimal.ONE));
    }
    return out;
  }
}
