package app.viglide.core.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Candle}. */
class CandleTest {

  private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
  private static final BigDecimal ONE = BigDecimal.ONE;
  private static final BigDecimal TWO = new BigDecimal("2");

  @Test
  void happyPath_constructsWithValidValues() {
    Candle c = new Candle(T0, ONE, TWO, ONE, ONE, ONE);
    assertThat(c.openTime()).isEqualTo(T0);
    assertThat(c.open()).isEqualByComparingTo("1");
    assertThat(c.high()).isEqualByComparingTo("2");
    assertThat(c.low()).isEqualByComparingTo("1");
    assertThat(c.close()).isEqualByComparingTo("1");
    assertThat(c.volume()).isEqualByComparingTo("1");
  }

  @Test
  void allowsZeroValues() {
    assertThatCode(
            () ->
                new Candle(
                    T0,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsNullOpenTime() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Candle(null, ONE, TWO, ONE, ONE, ONE))
        .withMessageContaining("openTime");
  }

  @Test
  void rejectsNullOpen() {
    assertThatNullPointerException().isThrownBy(() -> new Candle(T0, null, TWO, ONE, ONE, ONE));
  }

  @Test
  void rejectsNullHigh() {
    assertThatNullPointerException().isThrownBy(() -> new Candle(T0, ONE, null, ONE, ONE, ONE));
  }

  @Test
  void rejectsNullLow() {
    assertThatNullPointerException().isThrownBy(() -> new Candle(T0, ONE, TWO, null, ONE, ONE));
  }

  @Test
  void rejectsNullClose() {
    assertThatNullPointerException().isThrownBy(() -> new Candle(T0, ONE, TWO, ONE, null, ONE));
  }

  @Test
  void rejectsNullVolume() {
    assertThatNullPointerException().isThrownBy(() -> new Candle(T0, ONE, TWO, ONE, ONE, null));
  }

  @Test
  void rejectsNegativeOpen() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Candle(T0, ONE.negate(), TWO, ONE, ONE, ONE));
  }

  @Test
  void rejectsNegativeHigh() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Candle(T0, ONE, ONE.negate(), ONE, ONE, ONE));
  }

  @Test
  void rejectsNegativeLow() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Candle(T0, ONE, TWO, ONE.negate(), ONE, ONE));
  }

  @Test
  void rejectsNegativeClose() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Candle(T0, ONE, TWO, ONE, ONE.negate(), ONE));
  }

  @Test
  void rejectsNegativeVolume() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Candle(T0, ONE, TWO, ONE, ONE, ONE.negate()));
  }

  @Test
  void rejectsHighLessThanLow() {
    // high=1, low=2 → invalid
    assertThatIllegalArgumentException().isThrownBy(() -> new Candle(T0, ONE, ONE, TWO, ONE, ONE));
  }

  @Test
  void allowsHighEqualToLow() {
    assertThatCode(() -> new Candle(T0, ONE, ONE, ONE, ONE, ONE)).doesNotThrowAnyException();
  }
}
