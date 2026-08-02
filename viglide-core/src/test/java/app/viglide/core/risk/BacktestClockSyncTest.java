package app.viglide.core.risk;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.Factor;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BacktestClockSync}. */
class BacktestClockSyncTest {

  private static final Instant FAR_PAST = Instant.parse("2020-01-01T00:00:00Z");

  @Test
  void gate_advancesClockToContextAsOfBeforeDelegating() {
    MutableClock clock = new MutableClock(Instant.EPOCH, ZoneOffset.UTC);
    RiskManager rm = new RiskManager(RiskParameters.defaults(), clock);
    BacktestClockSync sync = new BacktestClockSync(rm, clock);

    // A signal from deep in the past would be refused as stale if the clock were still pinned at
    // EPOCH — approval here proves the sync advanced the clock to ctx.asOf() before delegating.
    MarketContext ctx =
        new MarketContext("BTCUSDT", CandleInterval.ONE_HOUR, candles(FAR_PAST, 20));
    TechnicalSignal signal =
        new TechnicalSignal(
            "BTCUSDT", Direction.BUY, 0.8, List.of(new Factor("T", "t", 1.0)), "t", ctx.asOf());
    PortfolioState state = PortfolioState.initial(new BigDecimal("10000"));

    ExecutionDecision decision = sync.gate(signal, state, ctx);

    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
    assertThat(clock.instant()).isEqualTo(ctx.asOf());
  }

  @Test
  void riskParameters_delegatesToWrappedPort() {
    MutableClock clock = new MutableClock(Instant.EPOCH, ZoneOffset.UTC);
    RiskParameters params = RiskParameters.defaults();
    RiskManager rm = new RiskManager(params, clock);
    BacktestClockSync sync = new BacktestClockSync(rm, clock);

    assertThat(sync.riskParameters()).isSameAs(params);
  }

  private static List<Candle> candles(Instant start, int count) {
    List<Candle> out = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      out.add(
          new Candle(
              start.plusSeconds(3600L * i),
              new BigDecimal("50000"),
              new BigDecimal("50100"),
              new BigDecimal("49900"),
              new BigDecimal("50000"),
              new BigDecimal("1000")));
    }
    return out;
  }
}
