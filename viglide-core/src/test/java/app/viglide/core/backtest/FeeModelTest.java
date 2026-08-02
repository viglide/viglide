package app.viglide.core.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import app.viglide.core.indicator.IndicatorMath;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FeeModel} (PLAN-008 Task E: fee-scale and maker/taker mode). */
class FeeModelTest {

  @Test
  void taker_matchesBinanceDefault() {
    assertThat(FeeModel.taker()).isEqualTo(FeeModel.binanceDefault());
    assertThat(FeeModel.taker().takerBps()).isEqualByComparingTo(FeeModel.TAKER_FEE_BPS);
    assertThat(FeeModel.taker().slippageBps()).isEqualByComparingTo(FeeModel.TAKER_SLIPPAGE_BPS);
  }

  @Test
  void maker_isCheaperThanTaker() {
    FeeModel maker = FeeModel.maker();
    FeeModel taker = FeeModel.taker();
    assertThat(maker.totalAdverseFactor(IndicatorMath.MC))
        .isLessThan(taker.totalAdverseFactor(IndicatorMath.MC));
    assertThat(maker.slippageBps()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void scaled_zeroDisablesCosts() {
    FeeModel scaled = FeeModel.taker().scaled(BigDecimal.ZERO, IndicatorMath.MC);
    assertThat(scaled.totalAdverseFactor(IndicatorMath.MC)).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void scaled_doublesEveryComponent() {
    FeeModel base = FeeModel.taker();
    FeeModel doubled = base.scaled(BigDecimal.valueOf(2), IndicatorMath.MC);
    assertThat(doubled.takerBps())
        .isEqualByComparingTo(base.takerBps().multiply(BigDecimal.valueOf(2)));
    assertThat(doubled.slippageBps())
        .isEqualByComparingTo(base.slippageBps().multiply(BigDecimal.valueOf(2)));
    assertThat(doubled.totalAdverseFactor(IndicatorMath.MC))
        .isEqualByComparingTo(
            base.totalAdverseFactor(IndicatorMath.MC).multiply(BigDecimal.valueOf(2)));
  }

  @Test
  void scaled_rejectsNegativeScale() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> FeeModel.taker().scaled(BigDecimal.valueOf(-1), IndicatorMath.MC));
  }

  @Test
  void constructor_rejectsNegativeBps() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new FeeModel(BigDecimal.valueOf(-1), BigDecimal.ZERO, BigDecimal.ZERO));
  }
}
