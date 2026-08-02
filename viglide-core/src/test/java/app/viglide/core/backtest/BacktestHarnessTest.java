package app.viglide.core.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.Factor;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.indicator.IndicatorMath;
import app.viglide.core.risk.ExecutionDecision;
import app.viglide.core.risk.PortfolioState;
import app.viglide.core.risk.RiskManagerPort;
import app.viglide.core.risk.RiskParameters;
import app.viglide.core.spi.StrategyMetadata;
import app.viglide.core.spi.TradingStrategy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** End-to-end harness tests using a synthetic, hand-computable strategy and dataset. */
class BacktestHarnessTest {

  // ── A toy strategy: emits BUY at bar N, SELL at bar M ────────────────────────────────────────

  private static final class ScheduledStrategy implements TradingStrategy {
    private final int buyAtBar;
    private final int sellAtBar;
    private int evalCount;

    ScheduledStrategy(int buyAtBar, int sellAtBar) {
      this.buyAtBar = buyAtBar;
      this.sellAtBar = sellAtBar;
    }

    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
      evalCount++;
      Direction d;
      if (evalCount == buyAtBar) d = Direction.BUY;
      else if (evalCount == sellAtBar) d = Direction.SELL;
      else d = Direction.HOLD;
      return Optional.of(
          new TechnicalSignal(
              ctx.symbol(),
              d,
              0.8,
              List.of(new Factor("TEST", "scheduled", 1.0)),
              "scheduled " + d,
              ctx.asOf()));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("Scheduled", "0.0.1", "test-only");
    }
  }

  // ── F3/F12: a recording RM stub captures the PortfolioState passed at each gate call ─────────

  /**
   * Records every {@link PortfolioState} it is gated with, alongside the mark price the harness
   * evaluated on, then approves a fixed-size Execute so the resulting position is hand-computable.
   */
  private static final class RecordingRiskManagerPort implements RiskManagerPort {
    private static final BigDecimal FIXED_SIZE = new BigDecimal("0.5");

    final List<PortfolioState> seenStates = new ArrayList<>();
    final List<BigDecimal> seenMarkPrices = new ArrayList<>();

    @Override
    public ExecutionDecision gate(TechnicalSignal signal, PortfolioState state, MarketContext ctx) {
      seenStates.add(state);
      BigDecimal markPrice = ctx.candles().getLast().close();
      seenMarkPrices.add(markPrice);
      BigDecimal slDistance = BigDecimal.ONE;
      BigDecimal stopLoss =
          signal.direction() == Direction.BUY
              ? markPrice.subtract(slDistance)
              : markPrice.add(slDistance);
      return new ExecutionDecision.Execute(
          signal.symbol(),
          signal.direction(),
          FIXED_SIZE,
          FIXED_SIZE.multiply(markPrice, IndicatorMath.MC),
          stopLoss,
          slDistance,
          Optional.empty(),
          "stub execute",
          List.of(),
          signal.asOf());
    }

    @Override
    public RiskParameters riskParameters() {
      return RiskParameters.defaults();
    }
  }

  @Test
  void rmGatedPath_portfolioStateCarriesNotionalNotEquityDelta() {
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 5, new BigDecimal("1.0"), null, null, 8760);
    List<Candle> candles = ascending(20, 100.0);
    RecordingRiskManagerPort stub = new RecordingRiskManagerPort();

    // BUY at eval 1 (flat), SELL at eval 3 (position opened by eval 1's fill is still open).
    BacktestHarness.run(
        new ScheduledStrategy(1, 3),
        candles.stream(),
        "BTCUSDT",
        CandleInterval.ONE_HOUR,
        cfg,
        Optional.of(stub));

    assertThat(stub.seenStates).hasSize(2);
    // First gate call: still flat, no open position yet.
    assertThat(stub.seenStates.get(0).openPositions()).isEmpty();
    // Second gate call: the BUY has filled, so the exchange holds FIXED_SIZE units. Notional must
    // be size × the mark price the harness evaluated on for THIS call — not equity − cash.
    PortfolioState secondState = stub.seenStates.get(1);
    BigDecimal expectedNotional =
        RecordingRiskManagerPort.FIXED_SIZE.multiply(stub.seenMarkPrices.get(1), IndicatorMath.MC);
    assertThat(secondState.openPositions().get("BTCUSDT")).isEqualByComparingTo(expectedNotional);
  }

  // ── Tests ────────────────────────────────────────────────────────────────────────────────────

  @Test
  void roundTrip_positiveTrendYieldsPositivePnl() {
    // Warmup = 5 bars. Strategy emits BUY at first eval (bar 5), SELL at eval 5 (bar 10).
    // Prices rise linearly 100, 101, ... so SELL price > BUY price.
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 5, new BigDecimal("1.0"), null, null, 8760);

    List<Candle> candles = ascending(20, 100.0);
    BacktestResult result =
        BacktestHarness.run(
            new ScheduledStrategy(1, 5), candles.stream(), "BTCUSDT", CandleInterval.ONE_HOUR, cfg);

    assertThat(result.tradeCount()).isEqualTo(1);
    assertThat(result.trades().get(0).pnl().signum()).isPositive();
    assertThat(result.totalReturn().signum()).isPositive();
    assertThat(result.winRate()).isEqualTo(1.0);
  }

  @Test
  void determinism_sameInputsProduceIdenticalResults() {
    BacktestConfig cfg = BacktestConfig.hourlyDefaults();
    List<Candle> candles = ascending(400, 100.0);

    BacktestResult r1 =
        BacktestHarness.run(
            new ScheduledStrategy(1, 50),
            candles.stream(),
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            cfg);
    BacktestResult r2 =
        BacktestHarness.run(
            new ScheduledStrategy(1, 50),
            candles.stream(),
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            cfg);

    assertThat(r1.endingEquity()).isEqualByComparingTo(r2.endingEquity());
    assertThat(r1.totalReturn()).isEqualByComparingTo(r2.totalReturn());
    assertThat(r1.maxDrawdown()).isEqualByComparingTo(r2.maxDrawdown());
    assertThat(r1.sharpe()).isEqualTo(r2.sharpe());
    assertThat(r1.tradeCount()).isEqualTo(r2.tradeCount());
  }

  @Test
  void warmupBarsDoNotGenerateTrades() {
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 10, new BigDecimal("1.0"), null, null, 8760);
    // Strategy wants to BUY at the very first evaluation — that happens at bar #10.
    List<Candle> candles = ascending(5, 100.0); // only 5 candles, never reaches warmup
    BacktestResult result =
        BacktestHarness.run(
            new ScheduledStrategy(1, 999),
            candles.stream(),
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            cfg);
    assertThat(result.tradeCount()).isEqualTo(0);
  }

  @Test
  void endOfData_closesOpenPosition() {
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 1, new BigDecimal("1.0"), null, null, 8760);
    List<Candle> candles = ascending(10, 100.0);
    // BUY at eval 1, never SELL ⇒ exchange must close at end of data.
    BacktestResult result =
        BacktestHarness.run(
            new ScheduledStrategy(1, 999),
            candles.stream(),
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            cfg);
    assertThat(result.tradeCount()).isEqualTo(1);
    assertThat(result.trades().get(0).exitReason()).isEqualTo(ExitReason.END_OF_DATA);
  }

  // ── PLAN-009 Task B2: harness-level sub-bar slicing ─────────────────────────────────────────

  @Test
  void subBarCandles_areSlicedPerDecisionBar_andResolveAmbiguousTpFirst() {
    // Same ambiguous-bar shape as VirtualExchangeTest's sub-bar fixture, but driven through the
    // full harness so the cursor-based slicing (candle openTime → its decision bar's window) is
    // exercised end-to-end, not just VirtualExchange's own direct-call API.
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
    List<Candle> candles =
        List.of(
            hourly(t0, 0, 100, 100, 100, 100), // eval 1 fires BUY (warmup=1)
            hourly(t0, 1, 100, 100, 100, 100), // BUY fills at 100 → SL 98, TP 105
            hourly(t0, 2, 100, 110, 97, 100), // ambiguous: coarse model would assume SL
            hourly(t0, 3, 100, 100, 100, 100));

    // Sub-bars for hour-0's and hour-1's windows are inert filler (position isn't open with an
    // active SL/TP yet); hour-2's sub-bars show TP touched before SL, in chronological order.
    List<Candle> subBars =
        List.of(
            minutely(t0, 0, 0, 100, 100, 100, 100),
            minutely(t0, 0, 30, 100, 100, 100, 100),
            minutely(t0, 1, 0, 100, 100, 100, 100),
            minutely(t0, 1, 30, 100, 100, 100, 100),
            minutely(t0, 2, 0, 100, 103, 99, 102), // neither SL nor TP yet
            minutely(t0, 2, 20, 102, 110, 101, 108), // TP (105) touched here
            minutely(t0, 2, 40, 108, 108, 97, 100)); // would-be SL, never reached

    BacktestResult result =
        BacktestHarness.run(
            new ScheduledStrategy(1, 999),
            candles.stream(),
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            cfg,
            List.of(),
            Optional.empty(),
            subBars);

    assertThat(result.tradeCount()).isEqualTo(1);
    Trade trade = result.trades().get(0);
    assertThat(trade.exitReason()).isEqualTo(ExitReason.TAKE_PROFIT);
    assertThat(trade.exitPrice()).isEqualByComparingTo("105");
  }

  @Test
  void subBarCandles_defaultEmpty_matchesPreExistingBehaviourExactly() {
    // Same ambiguous bar, no sub-bars passed at all (existing 7-arg overload) — must still
    // resolve to the coarse worst-case STOP_LOSS, proving the new parameter is opt-in only.
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
    List<Candle> candles =
        List.of(
            hourly(t0, 0, 100, 100, 100, 100),
            hourly(t0, 1, 100, 100, 100, 100),
            hourly(t0, 2, 100, 110, 97, 100),
            hourly(t0, 3, 100, 100, 100, 100));

    BacktestResult result =
        BacktestHarness.run(
            new ScheduledStrategy(1, 999),
            candles.stream(),
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            cfg,
            Optional.empty());

    assertThat(result.trades().get(0).exitReason()).isEqualTo(ExitReason.STOP_LOSS);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

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

  /** Builds an ascending-close candle series, 1 hour apart, with high = close + 0.1. */
  private static List<Candle> ascending(int count, double startClose) {
    List<Candle> out = new ArrayList<>(count);
    Instant start = Instant.parse("2024-01-01T00:00:00Z");
    for (int i = 0; i < count; i++) {
      double close = startClose + i;
      double prevClose = i == 0 ? close : startClose + (i - 1);
      out.add(
          new Candle(
              start.plusSeconds(3600L * i),
              BigDecimal.valueOf(prevClose),
              BigDecimal.valueOf(close + 0.1),
              BigDecimal.valueOf(close - 0.1),
              BigDecimal.valueOf(close),
              BigDecimal.valueOf(1000)));
    }
    return out;
  }
}
