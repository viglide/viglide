package app.viglide.core.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CircuitBreaker}. */
class CircuitBreakerTest {

  private static final BigDecimal MAX_DD = new BigDecimal("0.15");

  @Test
  void noLoss_doesNotTrip() {
    // Current equity equals peak — drawdown = 0 < 15%.
    assertThat(CircuitBreaker.shouldTrip(bd("10000"), bd("10000"), MAX_DD)).isFalse();
  }

  @Test
  void smallLoss_doesNotTrip() {
    // 5% drawdown < 15%.
    assertThat(CircuitBreaker.shouldTrip(bd("9500"), bd("10000"), MAX_DD)).isFalse();
  }

  @Test
  void exactThreshold_trips() {
    // Exactly 15% drawdown: (10000 - 8500) / 10000 = 0.15.
    assertThat(CircuitBreaker.shouldTrip(bd("8500"), bd("10000"), MAX_DD)).isTrue();
  }

  @Test
  void aboveThreshold_trips() {
    // 20% drawdown > 15%.
    assertThat(CircuitBreaker.shouldTrip(bd("8000"), bd("10000"), MAX_DD)).isTrue();
  }

  @Test
  void equityGoneNegative_trips() {
    // Equity < 0 always trips.
    assertThat(CircuitBreaker.shouldTrip(bd("-1"), bd("10000"), MAX_DD)).isTrue();
  }

  @Test
  void peakEquityZeroOrNegative_throws() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CircuitBreaker.shouldTrip(bd("8000"), BigDecimal.ZERO, MAX_DD));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CircuitBreaker.shouldTrip(bd("8000"), bd("-1"), MAX_DD));
  }

  @Test
  void maxDrawdownPctZero_throws() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CircuitBreaker.shouldTrip(bd("9500"), bd("10000"), BigDecimal.ZERO));
  }

  @Test
  void subsequentGains_afterTrip_irrelevant() {
    // Once tripped, state must be tracked externally. Confirm that a gain (equity > peak) is not
    // a trip even if checked again — CB is stateless.
    assertThat(CircuitBreaker.shouldTrip(bd("11000"), bd("10000"), MAX_DD)).isFalse();
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  // ── PLAN-012 Task F: shouldTripAbsolute ─────────────────────────────────────────────────────

  private static final BigDecimal MAX_LOSS_ABS = new BigDecimal("150");

  @Test
  void absolute_noLoss_doesNotTrip() {
    assertThat(CircuitBreaker.shouldTripAbsolute(bd("1000"), bd("1000"), MAX_LOSS_ABS)).isFalse();
  }

  @Test
  void absolute_smallLoss_doesNotTrip() {
    // $100 loss < $150 limit.
    assertThat(CircuitBreaker.shouldTripAbsolute(bd("900"), bd("1000"), MAX_LOSS_ABS)).isFalse();
  }

  @Test
  void absolute_exactThreshold_trips() {
    // Exactly $150 loss: 1000 - 850 = 150.
    assertThat(CircuitBreaker.shouldTripAbsolute(bd("850"), bd("1000"), MAX_LOSS_ABS)).isTrue();
  }

  @Test
  void absolute_aboveThreshold_trips() {
    assertThat(CircuitBreaker.shouldTripAbsolute(bd("700"), bd("1000"), MAX_LOSS_ABS)).isTrue();
  }

  @Test
  void absolute_gainAboveReference_doesNotTrip() {
    // A gain relative to the reference (e.g. campaign-start) equity is never a loss-trip, even
    // though it might exceed a *peak*-anchored percentage breaker computed separately.
    assertThat(CircuitBreaker.shouldTripAbsolute(bd("5000"), bd("1000"), MAX_LOSS_ABS)).isFalse();
  }

  @Test
  void absolute_referenceEquityZeroOrNegative_throws() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> CircuitBreaker.shouldTripAbsolute(bd("500"), BigDecimal.ZERO, MAX_LOSS_ABS));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CircuitBreaker.shouldTripAbsolute(bd("500"), bd("-1"), MAX_LOSS_ABS));
  }

  @Test
  void absolute_maxLossAbsZeroOrNegative_throws() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> CircuitBreaker.shouldTripAbsolute(bd("900"), bd("1000"), BigDecimal.ZERO));
  }
}
