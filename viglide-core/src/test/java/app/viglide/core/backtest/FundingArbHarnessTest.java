package app.viglide.core.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.Factor;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.spi.StrategyMetadata;
import app.viglide.core.spi.TradingStrategy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test for {@link FundingArbHarness}. Uses an always-in stub strategy plus synthetic
 * candles and funding events so the per-bar PnL is hand-computable.
 */
class FundingArbHarnessTest {

  // ── Stub: BUY at first evaluation, HOLD thereafter ───────────────────────────────────────────

  private static final class EnterOnceStrategy implements TradingStrategy {
    private boolean entered = false;

    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
      Direction d = entered ? Direction.HOLD : Direction.BUY;
      entered = true;
      return Optional.of(
          new TechnicalSignal(
              ctx.symbol(),
              d,
              0.9,
              List.of(new Factor("STUB", "enter once", 1.0)),
              "stub " + d,
              ctx.asOf()));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("EnterOnce", "0.0.1", "test-only");
    }
  }

  // ── Tests ────────────────────────────────────────────────────────────────────────────────────

  @Test
  void positionAccruesFundingWhileOpen() {
    // 30 candles, 5 funding events evenly spread. Strategy enters at bar 5 (first eval after the
    // 5-bar warmup) and never exits. Position notional = 2% × $10k = $200. With funding 0.0001
    // per event and 5 events firing while the position is open, expected funding income ≈ $200 ×
    // 5 × 0.0001 = $0.10. Entry + exit dual-leg fees = 2 × 10 bps × $200 × 2 = $0.80. Net change
    // is dominated by fees → small negative.
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.binanceDefault(),
            5, // warmup
            new BigDecimal("0.02"),
            null,
            null,
            1095);

    List<Candle> candles = candleSeries(30, 100.0);
    List<FundingEvent> funding = fundingSeries(5, candles, "0.0001");

    BacktestResult r =
        FundingArbHarness.run(
            new EnterOnceStrategy(), candles, funding, "BTCUSDT", CandleInterval.ONE_HOUR, cfg);

    assertThat(r.tradeCount()).isEqualTo(1); // single end-of-data exit
    assertThat(r.trades().get(0).exitReason()).isEqualTo(ExitReason.END_OF_DATA);
    // Ending equity should be close to starting cash; fees > accrued funding for tiny notionals.
    assertThat(r.endingEquity()).isLessThan(cfg.startingCash());
    assertThat(r.endingEquity()).isGreaterThan(cfg.startingCash().subtract(new BigDecimal("2")));
  }

  @Test
  void noPosition_noFundingAccruedNoEquityChange() {
    // Strategy never opens a position; equity stays flat at starting cash.
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 5, new BigDecimal("0.02"), null, null, 1095);
    List<Candle> candles = candleSeries(20, 100.0);
    List<FundingEvent> funding = fundingSeries(3, candles, "0.0010"); // big rate, but nobody trades

    BacktestResult r =
        FundingArbHarness.run(
            new HoldOnlyStrategy(), candles, funding, "BTCUSDT", CandleInterval.ONE_HOUR, cfg);

    assertThat(r.tradeCount()).isEqualTo(0);
    assertThat(r.endingEquity()).isEqualByComparingTo(cfg.startingCash());
  }

  @Test
  void determinism_sameInputsProduceIdenticalResults() {
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.binanceDefault(),
            5,
            new BigDecimal("0.02"),
            null,
            null,
            1095);
    List<Candle> candles = candleSeries(40, 100.0);
    List<FundingEvent> funding = fundingSeries(8, candles, "0.0002");

    BacktestResult a =
        FundingArbHarness.run(
            new EnterOnceStrategy(), candles, funding, "BTCUSDT", CandleInterval.ONE_HOUR, cfg);
    BacktestResult b =
        FundingArbHarness.run(
            new EnterOnceStrategy(), candles, funding, "BTCUSDT", CandleInterval.ONE_HOUR, cfg);

    assertThat(a.endingEquity()).isEqualByComparingTo(b.endingEquity());
    assertThat(a.totalReturn()).isEqualByComparingTo(b.totalReturn());
    assertThat(a.tradeCount()).isEqualTo(b.tradeCount());
  }

  // ── Stubs and helpers ────────────────────────────────────────────────────────────────────────

  private static final class HoldOnlyStrategy implements TradingStrategy {
    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
      return Optional.of(
          new TechnicalSignal(
              ctx.symbol(),
              Direction.HOLD,
              0.0,
              List.of(new Factor("STUB", "hold", 0.0)),
              "hold",
              ctx.asOf()));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("HoldOnly", "0.0.1", "test-only");
    }
  }

  private static List<Candle> candleSeries(int count, double close) {
    List<Candle> out = new ArrayList<>(count);
    Instant start = Instant.parse("2024-01-01T00:00:00Z");
    for (int i = 0; i < count; i++) {
      out.add(
          new Candle(
              start.plusSeconds(3600L * i),
              BigDecimal.valueOf(close),
              BigDecimal.valueOf(close + 0.1),
              BigDecimal.valueOf(close - 0.1),
              BigDecimal.valueOf(close),
              BigDecimal.ONE));
    }
    return out;
  }

  private static List<FundingEvent> fundingSeries(int count, List<Candle> candles, String rateStr) {
    BigDecimal rate = new BigDecimal(rateStr);
    List<FundingEvent> events = new ArrayList<>(count);
    int step = Math.max(1, candles.size() / (count + 1));
    for (int i = 1; i <= count; i++) {
      Instant t = candles.get(Math.min(i * step, candles.size() - 1)).openTime();
      events.add(new FundingEvent(t, rate));
    }
    return events;
  }
}
