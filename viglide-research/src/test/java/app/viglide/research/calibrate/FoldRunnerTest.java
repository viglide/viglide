package app.viglide.research.calibrate;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.BacktestResult;
import app.viglide.core.backtest.ExitReason;
import app.viglide.core.backtest.FeeModel;
import app.viglide.core.backtest.Trade;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.spi.StrategyMetadata;
import app.viglide.core.spi.TradingStrategy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * PLAN-009 Task C: {@link FoldRunner#defaultOhlcv(List)} must slice the global sub-bar series to
 * each fold's own window (inclusive of the last decision bar's full span) and forward it into the
 * harness, resolving SL/TP the same way a direct sub-bar-aware backtest would.
 */
class FoldRunnerTest {

  @Test
  void defaultOhlcvWithSubBars_resolvesAmbiguousBarUsingSlicedNeighborhood() {
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.zero(),
            1,
            new BigDecimal("1.0"),
            new BigDecimal("0.02"), // SL 2%
            new BigDecimal("0.05"), // TP 5%
            8760);

    Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
    List<Candle> chunk =
        List.of(
            hourly(t0, 0, 100, 100, 100, 100), // eval 1 fires BUY (warmup=1)
            hourly(t0, 1, 100, 100, 100, 100), // BUY fills at 100 -> SL 98, TP 105
            hourly(t0, 2, 100, 110, 97, 100), // ambiguous: coarse model would assume SL
            hourly(t0, 3, 100, 100, 100, 100));

    // Global sub-bar series (as if it covered the whole calibration dataset, not just this fold).
    List<Candle> allSubBars =
        List.of(
            minutely(t0, 2, 0, 100, 103, 99, 102),
            minutely(t0, 2, 20, 102, 110, 101, 108), // TP touched here first
            minutely(t0, 2, 40, 108, 108, 97, 100), // would-be SL, never reached
            minutely(t0, 2, 59, 100, 100, 100, 100)); // last minute of the ambiguous hour

    FoldRunner runner = FoldRunner.defaultOhlcv(allSubBars);
    BacktestResult result =
        runner.run(
            new AlwaysBuyOnFirstEvalStrategy(), chunk, "BTCUSDT", CandleInterval.ONE_HOUR, cfg);

    assertThat(result.tradeCount()).isEqualTo(1);
    Trade trade = result.trades().get(0);
    assertThat(trade.exitReason()).isEqualTo(ExitReason.TAKE_PROFIT);
    assertThat(trade.exitPrice()).isEqualByComparingTo("105");
  }

  @Test
  void defaultOhlcvWithSubBars_emptyGlobalList_matchesNoSubBarsBehaviour() {
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.zero(),
            1,
            new BigDecimal("1.0"),
            new BigDecimal("0.02"),
            new BigDecimal("0.05"),
            8760);
    Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
    List<Candle> chunk =
        List.of(
            hourly(t0, 0, 100, 100, 100, 100),
            hourly(t0, 1, 100, 100, 100, 100),
            hourly(t0, 2, 100, 110, 97, 100),
            hourly(t0, 3, 100, 100, 100, 100));

    FoldRunner runner = FoldRunner.defaultOhlcv(List.of());
    BacktestResult result =
        runner.run(
            new AlwaysBuyOnFirstEvalStrategy(), chunk, "BTCUSDT", CandleInterval.ONE_HOUR, cfg);

    assertThat(result.trades().get(0).exitReason()).isEqualTo(ExitReason.STOP_LOSS);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────────

  private static final class AlwaysBuyOnFirstEvalStrategy implements TradingStrategy {
    private boolean fired;

    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
      if (fired) return Optional.empty();
      fired = true;
      return Optional.of(
          new TechnicalSignal(ctx.symbol(), Direction.BUY, 0.8, List.of(), "buy once", ctx.asOf()));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("AlwaysBuyOnce", "0.0.1", "PLAN-009 Task C test-only");
    }
  }

  private static Candle hourly(
      Instant t0, long hourOffset, double open, double high, double low, double close) {
    return new Candle(
        t0.plusSeconds(hourOffset * 3600),
        BigDecimal.valueOf(open),
        BigDecimal.valueOf(high),
        BigDecimal.valueOf(low),
        BigDecimal.valueOf(close),
        BigDecimal.valueOf(1000));
  }

  private static Candle minutely(
      Instant t0,
      long hourOffset,
      long minuteOffset,
      double open,
      double high,
      double low,
      double close) {
    return new Candle(
        t0.plusSeconds(hourOffset * 3600 + minuteOffset * 60),
        BigDecimal.valueOf(open),
        BigDecimal.valueOf(high),
        BigDecimal.valueOf(low),
        BigDecimal.valueOf(close),
        BigDecimal.valueOf(50));
  }
}
