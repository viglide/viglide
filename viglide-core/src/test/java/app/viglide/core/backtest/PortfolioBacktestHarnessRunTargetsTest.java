package app.viglide.core.backtest;

import static org.assertj.core.api.Assertions.*;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.PortfolioContext;
import app.viglide.core.domain.PositionShape;
import app.viglide.core.domain.TargetPosition;
import app.viglide.core.indicator.IndicatorMath;
import app.viglide.core.risk.ExecutionDecision;
import app.viglide.core.risk.PortfolioState;
import app.viglide.core.risk.RiskManagerPort;
import app.viglide.core.risk.RiskParameters;
import app.viglide.core.spi.PortfolioStrategy;
import app.viglide.core.spi.StrategyMetadata;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * PLAN-015 Task B: tests for {@link PortfolioBacktestHarness#runTargets} — cross-sectional
 * rebalancing toward a {@link PortfolioStrategy}'s target weights.
 */
class PortfolioBacktestHarnessRunTargetsTest {

  private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");

  // ── Fakes ────────────────────────────────────────────────────────────────────────────────────

  /**
   * Applies a scripted weight sequence to every symbol present in context, one call per bar (a
   * single-weight script therefore behaves like "open once, then flat forever" for a multi-symbol
   * universe, and reduces to a plain per-bar sequence for a single-symbol universe). Returns empty
   * (flat) once the script is exhausted, never repeating the last value.
   */
  private static final class ScriptedEqualWeightStrategy implements PortfolioStrategy {
    private final List<BigDecimal> weights;
    private int calls = 0;

    ScriptedEqualWeightStrategy(BigDecimal... weights) {
      this.weights = List.of(weights);
    }

    @Override
    public List<TargetPosition> evaluate(PortfolioContext context) {
      int thisCall = calls++;
      if (thisCall >= weights.size()) {
        return List.of();
      }
      BigDecimal weight = weights.get(thisCall);
      List<TargetPosition> out = new ArrayList<>();
      context
          .bySymbol()
          .keySet()
          .forEach(
              symbol ->
                  out.add(
                      new TargetPosition(
                          symbol, weight, PositionShape.SPOT_ONLY, List.of(), "scripted weight")));
      return out;
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("scripted-weight-test", "1.0", "test-only");
    }
  }

  /** Approves BUY at a fixed notional (ignoring desired size), SELL unconditionally. */
  private static final class FixedNotionalRiskManagerPort implements RiskManagerPort {
    private final BigDecimal notionalPerBuy;

    FixedNotionalRiskManagerPort(BigDecimal notionalPerBuy) {
      this.notionalPerBuy = notionalPerBuy;
    }

    @Override
    public ExecutionDecision gate(
        app.viglide.core.domain.TechnicalSignal signal,
        PortfolioState state,
        app.viglide.core.domain.MarketContext ctx) {
      BigDecimal markPrice = ctx.candles().getLast().close();
      if (signal.direction() == Direction.BUY) {
        BigDecimal size = notionalPerBuy.divide(markPrice, IndicatorMath.MC);
        return new ExecutionDecision.Execute(
            signal.symbol(),
            Direction.BUY,
            size,
            notionalPerBuy,
            markPrice.subtract(BigDecimal.ONE),
            BigDecimal.ONE,
            Optional.empty(),
            "stub buy",
            List.of(),
            signal.asOf());
      }
      BigDecimal size = BigDecimal.ONE;
      return new ExecutionDecision.Execute(
          signal.symbol(),
          Direction.SELL,
          size,
          size.multiply(markPrice, IndicatorMath.MC),
          markPrice.add(BigDecimal.ONE),
          BigDecimal.ONE,
          Optional.empty(),
          "stub sell",
          List.of(),
          signal.asOf());
    }

    @Override
    public RiskParameters riskParameters() {
      return RiskParameters.defaults();
    }
  }

  // ── Tests ────────────────────────────────────────────────────────────────────────────────────

  @Test
  void twelveSymbolEqualWeightBook_opensRebalancesAndClosesWithExactFees() {
    int symbolCount = 12;
    Map<String, List<Candle>> candles = new LinkedHashMap<>();
    for (int i = 0; i < symbolCount; i++) {
      String symbol = "SYM" + i;
      candles.put(
          symbol,
          List.of(
              candle(T0, 100, 100), // bar0: opens equal-weight (desired 800)
              candle(T0.plusSeconds(3600), 100, 100), // bar1: rebalances to a bigger weight
              candle(T0.plusSeconds(7200), 100, 100))); // bar2: strategy goes flat -> closes
    }

    BigDecimal allocatedCapital = new BigDecimal("10000");
    // bar0=0.08 (desired 800, opens), bar1=0.5 (desired 5000, triggers close-then-reopen of the
    // existing lot -- the exact bug this test's rebalance step guards against), bar2=flat (closes).
    ScriptedEqualWeightStrategy strategy =
        new ScriptedEqualWeightStrategy(new BigDecimal("0.08"), new BigDecimal("0.5"));
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("100000"),
            FeeModel.binanceDefault(),
            1,
            new BigDecimal("1.0"),
            null,
            null,
            8760);

    // Fixed-notional RM: every approved BUY is 800 notional regardless of desired size, so the
    // fee arithmetic stays exact and predictable across every rebalance leg -- this test is about
    // proving the close-then-reopen mechanic and its fee accounting, not RM sizing.
    RiskManagerPort fixedRm = new FixedNotionalRiskManagerPort(new BigDecimal("800"));

    BacktestResult result =
        PortfolioBacktestHarness.runTargets(
            candles,
            strategy,
            CandleInterval.ONE_HOUR,
            cfg,
            fixedRm,
            allocatedCapital,
            BigDecimal.ZERO);

    // Per symbol: bar0 open (1 fee), bar1 rebalance = close + reopen (2 fees), bar2 close (1 fee)
    // = 4 fee events x 0.8 = 3.2; 2 Trade records (bar1's close, bar2's close). Flat price ->
    // total realised loss equals total fees exactly.
    assertThat(result.tradeCount()).isEqualTo(symbolCount * 2);
    for (Trade t : result.trades()) {
      assertThat(t.size()).isEqualByComparingTo("8");
      assertThat(t.entryPrice()).isEqualByComparingTo("100");
      assertThat(t.exitPrice()).isEqualByComparingTo("100");
      assertThat(t.exitReason()).isEqualTo(ExitReason.SIGNAL);
    }
    BigDecimal expectedEndingEquity = new BigDecimal("100000").subtract(new BigDecimal("38.4"));
    assertThat(result.endingEquity()).isEqualByComparingTo(expectedEndingEquity);
    assertThat(result.refusals()).isEmpty();
  }

  @Test
  void leverageBreach_scalesEveryOpenProportionally_neverRefusesIndividually() {
    int symbolCount = 4;
    Map<String, List<Candle>> candles = new LinkedHashMap<>();
    for (int i = 0; i < symbolCount; i++) {
      String symbol = "SYM" + i;
      candles.put(
          symbol,
          List.of(
              candle(T0, 100, 100), // bar0: all 4 request 10000 notional each (40000 total)
              candle(T0.plusSeconds(3600), 100, 100))); // bar1: strategy goes flat -> closes
    }

    // startingCash 10000, default maxLeverage 2x -> maxAllowed 20000. Each of 4 symbols requests
    // a fixed 10000 notional (sum 40000) -- individually each passes against the frozen
    // start-of-bar state (totalOpenNotional=0), so the breach can only be caught in aggregate.
    BigDecimal allocatedCapital = new BigDecimal("40000");
    ScriptedEqualWeightStrategy strategy = new ScriptedEqualWeightStrategy(new BigDecimal("0.25"));
    RiskManagerPort fixedRm = new FixedNotionalRiskManagerPort(new BigDecimal("10000"));
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 1, new BigDecimal("1.0"), null, null, 8760);

    BacktestResult result =
        PortfolioBacktestHarness.runTargets(
            candles,
            strategy,
            CandleInterval.ONE_HOUR,
            cfg,
            fixedRm,
            allocatedCapital,
            BigDecimal.ZERO);

    // No per-symbol refusal -- every symbol was admitted, just scaled down together.
    assertThat(result.refusals()).isEmpty();
    assertThat(result.diagnostics().get("bookScaleDowns")).isEqualTo(1L);

    // scaleFactor = 20000 / 40000 = 0.5 exactly -> each symbol's actual traded size is
    // 10000*0.5/100 = 50, not the unscaled 10000/100 = 100.
    assertThat(result.tradeCount()).isEqualTo(symbolCount);
    for (Trade t : result.trades()) {
      assertThat(t.size()).isEqualByComparingTo("50");
    }
  }

  @Test
  void noTradeBand_suppressesChurnOnSmallWeightChanges() {
    String symbol = "AAA";
    Map<String, List<Candle>> candles =
        Map.of(
            symbol,
            List.of(
                candle(T0, 100, 100), // bar0: weight 0.5 -> desired 5000, opens (current 0)
                candle(T0.plusSeconds(3600), 100, 100), // bar1: weight 0.52 -> delta 200, skip
                candle(T0.plusSeconds(7200), 100, 100), // bar2: weight 0.55 -> delta 500, skip
                candle(
                    T0.plusSeconds(10800),
                    100,
                    100))); // bar3: weight 0.65 -> delta 1500, rebalances

    BigDecimal allocatedCapital = new BigDecimal("10000");
    BigDecimal noTradeBand = new BigDecimal("0.1"); // 10% of allocatedCapital = 1000 notional
    ScriptedEqualWeightStrategy strategy =
        new ScriptedEqualWeightStrategy(
            new BigDecimal("0.5"),
            new BigDecimal("0.52"),
            new BigDecimal("0.55"),
            new BigDecimal("0.65"));
    RiskManagerPort fixedRm = new FixedNotionalRiskManagerPort(new BigDecimal("5000"));
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("100000"), FeeModel.zero(), 1, new BigDecimal("1.0"), null, null, 8760);

    BacktestResult result =
        PortfolioBacktestHarness.runTargets(
            candles,
            strategy,
            CandleInterval.ONE_HOUR,
            cfg,
            fixedRm,
            allocatedCapital,
            noTradeBand);

    // bar1 and bar2's small weight changes stay inside the no-trade band -> 2 skips.
    assertThat(result.diagnostics().get("noTradeBandSkips")).isEqualTo(2L);
    // bar3 crosses the band (rebalance: close the bar0 lot, reopen fresh) -> 1 trade from that
    // close, plus 1 more from the end-of-data sweep closing the freshly reopened lot.
    assertThat(result.tradeCount()).isEqualTo(2);
  }

  @Test
  void determinism_sameInputsProduceIdenticalResults() {
    Map<String, List<Candle>> candlesForward = new LinkedHashMap<>();
    candlesForward.put("AAA", series(10, 100.0));
    candlesForward.put("BBB", series(10, 50.0));

    Map<String, List<Candle>> candlesReversed = new LinkedHashMap<>();
    candlesReversed.put("BBB", series(10, 50.0));
    candlesReversed.put("AAA", series(10, 100.0));

    BigDecimal allocatedCapital = new BigDecimal("10000");
    RiskManagerPort fixedRm = new FixedNotionalRiskManagerPort(new BigDecimal("1000"));
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("100000"), FeeModel.zero(), 1, new BigDecimal("1.0"), null, null, 8760);

    BacktestResult r1 =
        PortfolioBacktestHarness.runTargets(
            candlesForward,
            new ScriptedEqualWeightStrategy(new BigDecimal("0.3")),
            CandleInterval.ONE_HOUR,
            cfg,
            fixedRm,
            allocatedCapital,
            BigDecimal.ZERO);
    BacktestResult r2 =
        PortfolioBacktestHarness.runTargets(
            candlesReversed,
            new ScriptedEqualWeightStrategy(new BigDecimal("0.3")),
            CandleInterval.ONE_HOUR,
            cfg,
            fixedRm,
            allocatedCapital,
            BigDecimal.ZERO);

    assertThat(r1).isEqualTo(r2);
  }

  @Test
  void rejectsDeltaNeutralCarryShape_untilTwoLegHarnessExtensionExists() {
    Map<String, List<Candle>> candles = Map.of("AAA", List.of(candle(T0, 100, 100)));
    PortfolioStrategy badShape =
        new PortfolioStrategy() {
          @Override
          public List<TargetPosition> evaluate(PortfolioContext context) {
            return List.of(
                new TargetPosition(
                    "AAA",
                    BigDecimal.ONE,
                    PositionShape.DELTA_NEUTRAL_CARRY,
                    List.of(),
                    "not supported here"));
          }

          @Override
          public StrategyMetadata metadata() {
            return new StrategyMetadata("bad-shape", "1.0", "test-only");
          }
        };
    RiskManagerPort fixedRm = new FixedNotionalRiskManagerPort(new BigDecimal("100"));
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 1, new BigDecimal("1.0"), null, null, 8760);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PortfolioBacktestHarness.runTargets(
                    candles,
                    badShape,
                    CandleInterval.ONE_HOUR,
                    cfg,
                    fixedRm,
                    new BigDecimal("10000"),
                    BigDecimal.ZERO));
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

  private static Candle candle(Instant t, double open, double close) {
    double hi = Math.max(open, close) + 0.5;
    double lo = Math.min(open, close) - 0.5;
    return new Candle(
        t,
        BigDecimal.valueOf(open),
        BigDecimal.valueOf(hi),
        BigDecimal.valueOf(lo),
        BigDecimal.valueOf(close),
        BigDecimal.valueOf(1000));
  }

  private static List<Candle> series(int count, double flatClose) {
    List<Candle> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      out.add(candle(T0.plusSeconds(3600L * i), flatClose, flatClose));
    }
    return out;
  }
}
