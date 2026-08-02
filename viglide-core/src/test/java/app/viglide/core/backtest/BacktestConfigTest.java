package app.viglide.core.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.ExchangeFilters;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * PLAN-009 Task B0/B1: {@link BacktestConfig#barsPerYearFor} derives the historically-hardcoded
 * 8760 default from the actual interval instead of assuming hourly bars.
 */
class BacktestConfigTest {

  // ── PLAN-010 Task D3: exchangeFilters parity knob ───────────────────────────────────────────

  @Test
  void exchangeFilters_absentByDefault_researchNumbersUnchanged() {
    assertThat(BacktestConfig.hourlyDefaults().exchangeFilters()).isEmpty();
  }

  @Test
  void exchangeFilters_explicitValueRoundTrips() {
    ExchangeFilters filters =
        new ExchangeFilters(new BigDecimal("5"), new BigDecimal("0.001"), new BigDecimal("0.10"));
    BacktestConfig base = BacktestConfig.hourlyDefaults();
    BacktestConfig withFilters =
        new BacktestConfig(
            base.startingCash(),
            base.fees(),
            base.warmupBars(),
            base.maxPositionPct(),
            base.stopLossPct(),
            base.takeProfitPct(),
            base.barsPerYear(),
            base.minHoldBars(),
            Optional.of(filters));
    assertThat(withFilters.exchangeFilters()).contains(filters);
  }

  @Test
  void barsPerYearFor_matchesHistoricalHourlyConstant() {
    assertThat(BacktestConfig.barsPerYearFor(CandleInterval.ONE_HOUR)).isEqualTo(8760);
  }

  @Test
  void barsPerYearFor_scalesInverselyWithIntervalLength() {
    assertThat(BacktestConfig.barsPerYearFor(CandleInterval.ONE_MINUTE)).isEqualTo(525_600);
    assertThat(BacktestConfig.barsPerYearFor(CandleInterval.FIVE_MINUTES)).isEqualTo(105_120);
    assertThat(BacktestConfig.barsPerYearFor(CandleInterval.FIFTEEN_MINUTES)).isEqualTo(35_040);
    assertThat(BacktestConfig.barsPerYearFor(CandleInterval.FOUR_HOURS)).isEqualTo(2_190);
    assertThat(BacktestConfig.barsPerYearFor(CandleInterval.ONE_DAY)).isEqualTo(365);
  }

  @Test
  void barsPerYearFor_rejectsNullInterval() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> BacktestConfig.barsPerYearFor(null))
        .isInstanceOf(NullPointerException.class);
  }
}
