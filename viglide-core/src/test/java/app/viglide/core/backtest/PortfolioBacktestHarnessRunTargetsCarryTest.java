package app.viglide.core.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.PortfolioContext;
import app.viglide.core.domain.PositionShape;
import app.viglide.core.domain.TargetPosition;
import app.viglide.core.indicator.IndicatorMath;
import app.viglide.core.params.JsonWriter;
import app.viglide.core.risk.BacktestClockSync;
import app.viglide.core.risk.ExecutionDecision;
import app.viglide.core.risk.MutableClock;
import app.viglide.core.risk.PortfolioState;
import app.viglide.core.risk.RiskManager;
import app.viglide.core.risk.RiskManagerPort;
import app.viglide.core.risk.RiskParameters;
import app.viglide.core.spi.PortfolioStrategy;
import app.viglide.core.spi.StrategyMetadata;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PortfolioBacktestHarness}'s two-leg {@code runTargets} overload (PLAN-019 Task
 * C): synthetic-fixture coverage of the mechanics {@link PortfolioBacktestHarnessRunTargetsTest}
 * cannot exercise (no spot leg, no funding, no liquidation guard, no carry/single-leg mixed book).
 * The regression anchor against PLAN-015 Task E's real-corpus numbers lives in private {@code
 * viglide-lab}, where {@code CarryRankingStrategy} is defined (PLAN-019 §3: public repo first).
 */
class PortfolioBacktestHarnessRunTargetsCarryTest {

  private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");

  // ── Fakes ────────────────────────────────────────────────────────────────────────────────────

  /** Emits a fixed DELTA_NEUTRAL_CARRY weight for one symbol, every bar it is in context. */
  private static final class ConstantCarryWeightStrategy implements PortfolioStrategy {
    private final String symbol;
    private final BigDecimal weight;

    ConstantCarryWeightStrategy(String symbol, BigDecimal weight) {
      this.symbol = symbol;
      this.weight = weight;
    }

    @Override
    public List<TargetPosition> evaluate(PortfolioContext context) {
      if (!context.bySymbol().containsKey(symbol)) {
        return List.of();
      }
      return List.of(
          new TargetPosition(
              symbol, weight, PositionShape.DELTA_NEUTRAL_CARRY, List.of(), "constant carry"));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("constant-carry-weight-test", "1.0", "test-only");
    }
  }

  /** Scripted per-bar target lists, one call per bar; empty (flat) once the script is exhausted. */
  private static final class ScriptedTargetsStrategy implements PortfolioStrategy {
    private final List<List<TargetPosition>> script;
    private int calls = 0;

    ScriptedTargetsStrategy(List<List<TargetPosition>> script) {
      this.script = script;
    }

    @Override
    public List<TargetPosition> evaluate(PortfolioContext context) {
      int thisCall = calls++;
      return thisCall < script.size() ? script.get(thisCall) : List.of();
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("scripted-targets-test", "1.0", "test-only");
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
        app.viglide.core.domain.TechnicalSignal signal, PortfolioState state, MarketContext ctx) {
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
  void openAccrueClose_matchesHandComputedTwoLegAccounting() {
    String symbol = "AAA";
    List<Candle> perp =
        List.of(
            candle(T0, 100), // bar0: opens (desired 10000, current 0)
            candle(T0.plusSeconds(3600), 100), // bar1: funding accrues, held (no-trade-band)
            candle(T0.plusSeconds(7200), 100)); // bar2: strategy goes flat -> closes
    List<Candle> spot =
        List.of(
            candle(T0, 100), candle(T0.plusSeconds(3600), 100), candle(T0.plusSeconds(7200), 100));
    List<FundingEvent> funding =
        List.of(new FundingEvent(T0.plusSeconds(3600), new BigDecimal("0.001")));

    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("100000"), FeeModel.zero(), 1, new BigDecimal("1.0"), null, null, 8760);
    RiskManagerPort fixedRm = new FixedNotionalRiskManagerPort(new BigDecimal("10000"));

    TargetPosition carryTarget =
        new TargetPosition(
            symbol, BigDecimal.ONE, PositionShape.DELTA_NEUTRAL_CARRY, List.of(), "carry");
    // bar0: opens. bar1: same weight -> desired == current, no-trade-band holds it open while
    // funding accrues. bar2: empty list -> absence means flat (TargetPosition's own contract) ->
    // closes.
    ScriptedTargetsStrategy strategy =
        new ScriptedTargetsStrategy(List.of(List.of(carryTarget), List.of(carryTarget), List.of()));

    BacktestResult result =
        PortfolioBacktestHarness.runTargets(
            Map.of(symbol, perp),
            Map.of(symbol, spot),
            Map.of(symbol, funding),
            Map.of(),
            strategy,
            CandleInterval.ONE_HOUR,
            cfg,
            fixedRm,
            new BigDecimal("10000"),
            BigDecimal.ZERO);

    assertThat(result.tradeCount()).isEqualTo(1);
    Trade trade = result.trades().get(0);
    assertThat(trade.exitReason()).isEqualTo(ExitReason.SIGNAL);
    assertThat(trade.size()).isEqualByComparingTo("100"); // q = 10000 / spot.close(100)
    assertThat(trade.entryPrice()).isEqualByComparingTo("100");
    assertThat(trade.exitPrice()).isEqualByComparingTo("100");
    // Flat spot+perp, zero fees -> the only P&L is the single funding accrual: q * rate * price
    // = 100 * 0.001 * 100 = 10.
    assertThat(trade.pnl()).isEqualByComparingTo("10");
    assertThat(result.endingEquity()).isEqualByComparingTo("100010");
    assertThat(result.diagnostics().get("liquidations")).isEqualTo(0L);
    assertThat(result.diagnostics().get("totalFeesPaid")).isEqualTo(BigDecimal.ZERO);
    assertThat(result.refusals()).isEmpty();
  }

  @Test
  void liquidationGuard_firesForCarryPosition_otherCarrySymbolUnaffected() {
    int warmupBars = 5;
    // Mirrors PortfolioFundingArbHarnessV2Test's own ramp fixture exactly: entry 100, notional
    // 5000 -> q=50, margin = 50*100/2 = 2500, 90%-buffer trips at perpClose >= 145; first close
    // reaching that is 150.
    List<Candle> perpAaa = new ArrayList<>(flatCandles(5, new BigDecimal("100")));
    BigDecimal[] rampCloses = {
      new BigDecimal("110"), new BigDecimal("120"), new BigDecimal("130"),
      new BigDecimal("140"), new BigDecimal("150"), new BigDecimal("160")
    };
    for (int i = 0; i < rampCloses.length; i++) {
      Instant t = T0.plus(Duration.ofHours(5 + i));
      perpAaa.add(
          new Candle(
              t, rampCloses[i], rampCloses[i], rampCloses[i], rampCloses[i], BigDecimal.ONE));
    }
    List<Candle> spotAaa = new ArrayList<>();
    for (Candle c : perpAaa) {
      spotAaa.add(new Candle(c.openTime(), c.open(), c.high(), c.low(), c.close(), c.volume()));
    }
    List<Candle> flatBbb = flatCandles(11, new BigDecimal("50"));

    Map<String, List<Candle>> perpBySymbol = new LinkedHashMap<>();
    perpBySymbol.put("AAA", perpAaa);
    perpBySymbol.put("BBB", flatBbb);
    Map<String, List<Candle>> spotBySymbol = new LinkedHashMap<>();
    spotBySymbol.put("AAA", spotAaa);
    spotBySymbol.put("BBB", flatBbb);

    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.zero(),
            warmupBars,
            new BigDecimal("1.0"),
            null,
            null,
            8760);
    RiskManagerPort fixedRm = new FixedNotionalRiskManagerPort(new BigDecimal("5000"));

    BacktestResult result =
        PortfolioBacktestHarness.runTargets(
            perpBySymbol,
            spotBySymbol,
            Map.of(),
            Map.of(),
            new ConstantCarryWeightStrategy("AAA", BigDecimal.ONE),
            CandleInterval.ONE_HOUR,
            cfg,
            fixedRm,
            new BigDecimal("5000"),
            // Wide (but not 1.0 -- that would swallow the opening trade itself, since current
            // starts at 0 and the initial delta equals allocatedCapital exactly) band: this
            // test's job is the liquidation guard, not the rebalance-diff logic -- price-driven
            // mark drift on a held position (up to 2000 across the pre-liquidation ramp bars)
            // must not itself trigger a close before the ramp ever reaches the liquidation
            // threshold. A no-trade band this wide is test scaffolding, not production guidance
            // -- PLAN-019 Task D's calibration search covers that parameter for real.
            new BigDecimal("0.9"));

    assertThat(result.diagnostics().get("liquidations")).isEqualTo(1L);
    assertThat(result.diagnostics().get("trades.BBB")).isEqualTo(0L);
    long liquidationTrades =
        result.trades().stream()
            .filter(t -> t.exitReason() == ExitReason.LIQUIDATION_GUARD)
            .count();
    assertThat(liquidationTrades).isEqualTo(1);

    @SuppressWarnings("unchecked")
    List<LiquidationEvent> events =
        (List<LiquidationEvent>) result.diagnostics().get("liquidationEvents");
    assertThat(events).hasSize(1);
    assertThat(events.get(0).symbol()).isEqualTo("AAA");
    assertThat(events.get(0).perpLoss()).isGreaterThanOrEqualTo(events.get(0).marginThreshold());

    // This overload is the SECOND producer of the "liquidationEvents" key (PortfolioFundingArbHarn-
    // essV2 is the first). JsonWriter accepts only null/String/Boolean/Number/Map/List and throws
    // on anything else, so any caller serialising diagnostics wholesale blows up -- but only on a
    // run that actually liquidated, and only after the whole backtest has completed. PortfolioCli
    // learned that the hard way (viglide#12); pin the contract here too so the next caller finds
    // out from a unit test rather than at the end of a multi-hour run.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> JsonWriter.pretty(result.diagnostics()))
        .withMessageContaining(LiquidationEvent.class.getName());
    Map<String, Object> serialisable = new LinkedHashMap<>(result.diagnostics());
    serialisable.remove("liquidationEvents");
    assertThat(JsonWriter.pretty(serialisable)).contains("liquidations");
  }

  @Test
  void mixedBook_singleLegAndCarry_shareCashAndBookLevelScaleDown() {
    List<Candle> two =
        List.of(candle(T0, 100), candle(T0.plusSeconds(3600), 100)); // bar0 open, bar1 flat/close
    Map<String, List<Candle>> perp = new LinkedHashMap<>();
    perp.put("SPOTSYM", two);
    perp.put("CARRYSYM", two);
    Map<String, List<Candle>> spot = Map.of("CARRYSYM", two);

    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 1, new BigDecimal("1.0"), null, null, 8760);
    RiskManagerPort fixedRm = new FixedNotionalRiskManagerPort(new BigDecimal("20000"));

    List<TargetPosition> bar0Targets =
        List.of(
            new TargetPosition(
                "SPOTSYM", new BigDecimal("0.5"), PositionShape.SPOT_ONLY, List.of(), "spot"),
            new TargetPosition(
                "CARRYSYM",
                new BigDecimal("0.5"),
                PositionShape.DELTA_NEUTRAL_CARRY,
                List.of(),
                "carry"));
    ScriptedTargetsStrategy strategy = new ScriptedTargetsStrategy(List.of(bar0Targets));

    BacktestResult result =
        PortfolioBacktestHarness.runTargets(
            perp,
            spot,
            Map.of(),
            Map.of(),
            strategy,
            CandleInterval.ONE_HOUR,
            cfg,
            fixedRm,
            new BigDecimal("40000"),
            BigDecimal.ZERO);

    // startingCash 10000, default maxLeverage 2x -> maxAllowed 20000. Each symbol individually
    // requests 20000 (sum 40000) -- only the aggregate check catches this.
    assertThat(result.refusals()).isEmpty();
    assertThat(result.diagnostics().get("bookScaleDowns")).isEqualTo(1L);
    assertThat(result.tradeCount()).isEqualTo(2);
    for (Trade t : result.trades()) {
      // scaleFactor = 20000/40000 = 0.5 -> each leg's actual size is 100, not the unscaled 200.
      assertThat(t.size()).isEqualByComparingTo("100");
    }
  }

  @Test
  void deltaNeutralCarryTarget_withoutSpotData_throwsImmediately() {
    Map<String, List<Candle>> perp = Map.of("AAA", List.of(candle(T0, 100)));
    RiskManagerPort fixedRm = new FixedNotionalRiskManagerPort(new BigDecimal("100"));
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 1, new BigDecimal("1.0"), null, null, 8760);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PortfolioBacktestHarness.runTargets(
                    perp,
                    Map.of(), // no spot data supplied for AAA
                    Map.of(),
                    Map.of(),
                    new ConstantCarryWeightStrategy("AAA", BigDecimal.ONE),
                    CandleInterval.ONE_HOUR,
                    cfg,
                    fixedRm,
                    new BigDecimal("10000"),
                    BigDecimal.ZERO))
        .withMessageContaining("no spot data was supplied");
  }

  @Test
  void perpShortTarget_stillHasNoExecutionPath() {
    Map<String, List<Candle>> perp = Map.of("AAA", List.of(candle(T0, 100)));
    Map<String, List<Candle>> spot = Map.of("AAA", List.of(candle(T0, 100)));
    PortfolioStrategy perpShort =
        new PortfolioStrategy() {
          @Override
          public List<TargetPosition> evaluate(PortfolioContext context) {
            return List.of(
                new TargetPosition(
                    "AAA", BigDecimal.ONE.negate(), PositionShape.PERP_SHORT, List.of(), "short"));
          }

          @Override
          public StrategyMetadata metadata() {
            return new StrategyMetadata("perp-short-test", "1.0", "test-only");
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
                    perp,
                    spot,
                    Map.of(),
                    Map.of(),
                    perpShort,
                    CandleInterval.ONE_HOUR,
                    cfg,
                    fixedRm,
                    new BigDecimal("10000"),
                    BigDecimal.ZERO))
        .withMessageContaining("PERP_SHORT");
  }

  @Test
  void determinism_sameInputsProduceIdenticalResults() {
    List<Candle> aaa = series(10, 100.0);
    List<Candle> bbb = series(10, 50.0);

    Map<String, List<Candle>> perpForward = new LinkedHashMap<>();
    perpForward.put("AAA", aaa);
    perpForward.put("BBB", bbb);
    Map<String, List<Candle>> perpReversed = new LinkedHashMap<>();
    perpReversed.put("BBB", bbb);
    perpReversed.put("AAA", aaa);
    Map<String, List<Candle>> spotForward = new LinkedHashMap<>();
    spotForward.put("AAA", aaa);
    spotForward.put("BBB", bbb);
    Map<String, List<Candle>> spotReversed = new LinkedHashMap<>();
    spotReversed.put("BBB", bbb);
    spotReversed.put("AAA", aaa);

    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("100000"), FeeModel.zero(), 1, new BigDecimal("1.0"), null, null, 8760);
    RiskManagerPort fixedRm = new FixedNotionalRiskManagerPort(new BigDecimal("1000"));

    BacktestResult r1 =
        PortfolioBacktestHarness.runTargets(
            perpForward,
            spotForward,
            Map.of(),
            Map.of(),
            new ConstantCarryWeightStrategy("AAA", new BigDecimal("0.3")),
            CandleInterval.ONE_HOUR,
            cfg,
            fixedRm,
            new BigDecimal("10000"),
            BigDecimal.ZERO);
    BacktestResult r2 =
        PortfolioBacktestHarness.runTargets(
            perpReversed,
            spotReversed,
            Map.of(),
            Map.of(),
            new ConstantCarryWeightStrategy("AAA", new BigDecimal("0.3")),
            CandleInterval.ONE_HOUR,
            cfg,
            fixedRm,
            new BigDecimal("10000"),
            BigDecimal.ZERO);

    assertThat(r1).isEqualTo(r2);
  }

  @Test
  void noTradeBandFix_stableTargetHoldsOnce_underRealRiskManagerCap() {
    // PLAN-019 Task C review finding, root-caused in
    // docs/notes/2026-08-07-plan019-task-c-runtargets-two-leg.md and fixed per
    // docs/notes/2026-08-07-plan019-runtargets-notradeband-fix.md: with a REAL RiskManager (not a
    // fixed-notional stub), targetWeight=1.0 on a $10,000 book asks for a $10,000 position, but
    // RiskParameters.defaults()'s maxPositionPct=0.02 caps any single position at ~$200 -- a ~50x
    // gap that, before the fix, was re-litigated against noTradeBand every single bar (the exact
    // "144,165 trades, 3.0/bar = k" churn the regression anchor found). This test reproduces that
    // gap directly and asserts it no longer churns: the position opens once and is held, even
    // though price keeps moving (so the RM-approved notional keeps moving too) and the target
    // weight never changes.
    String symbol = "AAA";
    int bars = 40;
    List<Candle> alternating = new ArrayList<>(bars);
    List<Candle> spotAlternating = new ArrayList<>(bars);
    for (int i = 0; i < bars; i++) {
      BigDecimal price = new BigDecimal(i % 2 == 0 ? "100" : "101");
      Instant t = T0.plusSeconds(3600L * i);
      // Real, generous volume -- RiskManager's daily-volume cap (0.5% of a 24-bar sum) must not
      // interfere; this test is about the notional-band mismatch, not the volume cap.
      Candle c = new Candle(t, price, price, price, price, new BigDecimal("1000000"));
      alternating.add(c);
      spotAlternating.add(c);
    }

    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 15, new BigDecimal("1.0"), null, null, 8760);
    MutableClock clock = new MutableClock(Instant.EPOCH, ZoneOffset.UTC);
    RiskManagerPort realRm =
        new BacktestClockSync(new RiskManager(RiskParameters.defaults(), clock), clock);

    BacktestResult result =
        PortfolioBacktestHarness.runTargets(
            Map.of(symbol, alternating),
            Map.of(symbol, spotAlternating),
            Map.of(),
            Map.of(),
            new ConstantCarryWeightStrategy(symbol, BigDecimal.ONE),
            CandleInterval.ONE_HOUR,
            cfg,
            realRm,
            new BigDecimal("10000"),
            new BigDecimal("0.05"));

    // One open, held for the rest of the run despite price moving every bar (so the RM-approved
    // notional moves too) and despite the target weight never changing -- not one trade per bar.
    assertThat(result.tradeCount()).isEqualTo(1);
    assertThat(result.refusals()).isEmpty();
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

  private static Candle candle(Instant t, double price) {
    BigDecimal p = BigDecimal.valueOf(price);
    return new Candle(t, p, p, p, p, BigDecimal.ONE);
  }

  private static List<Candle> flatCandles(int n, BigDecimal price) {
    List<Candle> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      out.add(new Candle(T0.plus(Duration.ofHours(i)), price, price, price, price, BigDecimal.ONE));
    }
    return out;
  }

  private static List<Candle> series(int count, double flatClose) {
    List<Candle> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      out.add(candle(T0.plusSeconds(3600L * i), flatClose));
    }
    return out;
  }
}
