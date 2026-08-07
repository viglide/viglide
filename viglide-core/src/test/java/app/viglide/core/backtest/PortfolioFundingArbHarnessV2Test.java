package app.viglide.core.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.Factor;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.PremiumIndexEvent;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.indicator.IndicatorMath;
import app.viglide.core.params.JsonWriter;
import app.viglide.core.risk.ExecutionDecision;
import app.viglide.core.risk.PortfolioState;
import app.viglide.core.risk.RiskManagerPort;
import app.viglide.core.risk.RiskParameters;
import app.viglide.core.spi.StrategyMetadata;
import app.viglide.core.spi.TradingStrategy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PortfolioFundingArbHarnessV2} (PLAN-011 Task F, finding F6). Covers the two
 * properties a single-symbol two-leg harness structurally cannot exercise (mirroring {@link
 * PortfolioBacktestHarnessTest}'s framing for the v1 model) plus the ported-over two-leg mechanics
 * (funding, liquidation guard, sub-bar realism) verified against {@link FundingArbHarnessV2Test}'s
 * own known-good numbers.
 */
class PortfolioFundingArbHarnessV2Test {

  private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");

  // ── Fakes ────────────────────────────────────────────────────────────────────────────────────

  /** BUY on the first evaluation only, HOLD forever after. */
  private static final class BuyOnceStrategy implements TradingStrategy {
    private boolean bought;

    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
      Direction d = bought ? Direction.HOLD : Direction.BUY;
      bought = true;
      return Optional.of(
          new TechnicalSignal(
              ctx.symbol(),
              d,
              0.9,
              List.of(new Factor("TEST", "buy-once", 1.0)),
              "buy once",
              ctx.asOf()));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("BuyOnce", "0.0.1", "test-only");
    }
  }

  /** Emits the scripted direction on the Nth evaluation (0-indexed); HOLD otherwise. */
  private static final class ScriptedStrategy implements TradingStrategy {
    private final Map<Integer, Direction> script;
    private int callCount = 0;

    ScriptedStrategy(Map<Integer, Direction> script) {
      this.script = script;
    }

    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
      Direction d = script.getOrDefault(callCount, Direction.HOLD);
      callCount++;
      return Optional.of(
          new TechnicalSignal(
              ctx.symbol(),
              d,
              0.9,
              List.of(new Factor("TEST", "scripted", 1.0)),
              "scripted",
              ctx.asOf()));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("Scripted", "0.0.1", "test-only");
    }
  }

  /** Never trades — a HOLD-only strategy used as an inert co-tenant in a shared portfolio. */
  private static final class NeverTradeStrategy implements TradingStrategy {
    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
      return Optional.of(
          new TechnicalSignal(
              ctx.symbol(), Direction.HOLD, 0.9, List.of(), "never trades", ctx.asOf()));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("NeverTrade", "0.0.1", "test-only");
    }
  }

  /**
   * Approves BUY at {@code requestedFraction × equity} notional, refusing with {@code
   * LEVERAGE_CAP_EXCEEDED} once aggregate open notional (across every symbol — read from {@code
   * state.totalOpenNotional()}) would exceed {@code maxLeverage × equity}. Mirrors {@link
   * PortfolioBacktestHarnessTest}'s equivalent exactly — the point is proving this harness's own
   * portfolio-shared {@code PortfolioState} threading works the same way.
   */
  private static final class LeverageCheckingRiskManagerPort implements RiskManagerPort {
    private final BigDecimal requestedFraction;

    LeverageCheckingRiskManagerPort(BigDecimal requestedFraction) {
      this.requestedFraction = requestedFraction;
    }

    @Override
    public ExecutionDecision gate(TechnicalSignal signal, PortfolioState state, MarketContext ctx) {
      BigDecimal markPrice = ctx.candles().getLast().close();
      BigDecimal requestedNotional = state.equity().multiply(requestedFraction, IndicatorMath.MC);
      BigDecimal maxNotional =
          state.equity().multiply(riskParameters().maxLeverage(), IndicatorMath.MC);
      BigDecimal totalNotional = state.totalOpenNotional().add(requestedNotional, IndicatorMath.MC);
      if (totalNotional.compareTo(maxNotional) > 0) {
        return new ExecutionDecision.Refuse(
            signal.symbol(),
            ExecutionDecision.RefusalReason.LEVERAGE_CAP_EXCEEDED,
            "total notional " + totalNotional + " would exceed " + maxNotional,
            signal.asOf());
      }
      BigDecimal size = requestedNotional.divide(markPrice, IndicatorMath.MC);
      BigDecimal slDistance = BigDecimal.ONE;
      return new ExecutionDecision.Execute(
          signal.symbol(),
          Direction.BUY,
          size,
          requestedNotional,
          markPrice.subtract(slDistance),
          slDistance,
          Optional.empty(),
          "stub leverage-checking execute",
          List.of(),
          signal.asOf());
    }

    @Override
    public RiskParameters riskParameters() {
      return RiskParameters.defaults();
    }
  }

  // ── Test 1: two-leg math + funding, ported unchanged into a shared portfolio ────────────────

  @Test
  void flatPricesConstantFunding_matchesSingleSymbolMath_whileASecondSymbolCoexists() {
    // Exactly FundingArbHarnessV2Test's flatPricesConstantFunding_... fixture for symbol AAA
    // (q=50, 3 funding events of 0.001, expected equity 10000+15-20=9995) plus an inert BBB that
    // never trades -- proves the portfolio wrapper doesn't disturb the ported two-leg math.
    int warmupBars = 5;
    List<Candle> perp = flatCandles(20, new BigDecimal("100"));
    List<Candle> spot = flatCandles(20, new BigDecimal("100"));

    Map<String, List<Candle>> perpBySymbol = new LinkedHashMap<>();
    perpBySymbol.put("AAA", perp);
    perpBySymbol.put("BBB", perp);
    Map<String, List<Candle>> spotBySymbol = new LinkedHashMap<>();
    spotBySymbol.put("AAA", spot);
    spotBySymbol.put("BBB", spot);

    Map<String, List<FundingEvent>> fundingBySymbol = new LinkedHashMap<>();
    fundingBySymbol.put(
        "AAA",
        List.of(
            new FundingEvent(perp.get(6).openTime(), new BigDecimal("0.001")),
            new FundingEvent(perp.get(8).openTime(), new BigDecimal("0.001")),
            new FundingEvent(perp.get(10).openTime(), new BigDecimal("0.001"))));

    Map<String, TradingStrategy> strategies = new LinkedHashMap<>();
    strategies.put("AAA", new ScriptedStrategy(Map.of(0, Direction.BUY, 11, Direction.SELL)));
    strategies.put("BBB", new NeverTradeStrategy());

    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.taker(),
            warmupBars,
            new BigDecimal("0.5"),
            null,
            null,
            8760);
    RiskManagerPort rm = fixedNotionalRm(new BigDecimal("5000")); // q = 5000/100 = 50, matches v2

    BacktestResult result =
        PortfolioFundingArbHarnessV2.run(
            perpBySymbol,
            spotBySymbol,
            fundingBySymbol,
            strategies,
            CandleInterval.ONE_HOUR,
            cfg,
            rm);

    BigDecimal expectedEquity =
        new BigDecimal("10000").add(new BigDecimal("15")).subtract(new BigDecimal("20"));
    assertThat(result.endingEquity()).isEqualByComparingTo(expectedEquity);
    assertThat(result.tradeCount()).isEqualTo(1); // only AAA traded
    assertThat(result.trades().get(0).exitReason()).isEqualTo(ExitReason.SIGNAL);
  }

  // ── Test 2: the entire point of this class — shared-margin cross-symbol gating ─────────────

  @Test
  void aggregateLeverage_secondSymbolRefusedDueToFirstSymbolsExposure() {
    Map<String, List<Candle>> perpBySymbol = new LinkedHashMap<>();
    perpBySymbol.put("AAA", List.of(candle(T0, "100")));
    perpBySymbol.put("BBB", List.of(candle(T0, "100")));
    Map<String, List<Candle>> spotBySymbol = new LinkedHashMap<>();
    spotBySymbol.put("AAA", List.of(candle(T0, "100")));
    spotBySymbol.put("BBB", List.of(candle(T0, "100")));

    Map<String, TradingStrategy> strategies = new LinkedHashMap<>();
    strategies.put("AAA", new BuyOnceStrategy());
    strategies.put("BBB", new BuyOnceStrategy());

    LeverageCheckingRiskManagerPort rm = new LeverageCheckingRiskManagerPort(new BigDecimal("1.2"));
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 1, new BigDecimal("1.0"), null, null, 8760);

    BacktestResult result =
        PortfolioFundingArbHarnessV2.run(
            perpBySymbol, spotBySymbol, Map.of(), strategies, CandleInterval.ONE_HOUR, cfg, rm);

    // AAA's 1.2x-equity request is within the 2x cap (aggregate so far: 0). BBB's identical
    // request would bring the aggregate to 2.4x -- over the cap -- so only BBB is refused. This is
    // the property FundingArbHarnessV2 (single-symbol) cannot exercise at all: its own gate call
    // always sees Map.of() for other symbols, so BBB would have been silently approved there.
    assertThat(result.refusals()).hasSize(1);
    assertThat(result.refusals().get(0).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.LEVERAGE_CAP_EXCEEDED);
    assertThat(result.refusals().get(0).symbol()).isEqualTo("BBB");
    assertThat(result.tradeCount()).isEqualTo(1);
  }

  // ── Test 3: liquidation guard fires per-symbol, independently ───────────────────────────────

  @Test
  void liquidationGuard_firesOnOneSymbolOnly_otherSymbolUnaffected() {
    int warmupBars = 5;
    // AAA ramps toward liquidation exactly like FundingArbHarnessV2Test's own ramp fixture
    // (entry 100, margin = q*100/2 = 2500, 90%-buffer trips at perpClose >= 145; first close
    // reaching that is 150, bar index 9). BBB stays flat throughout and is never touched.
    List<Candle> perpAaa = new java.util.ArrayList<>(flatCandles(5, new BigDecimal("100")));
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
    List<Candle> spotAaa = new java.util.ArrayList<>();
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

    Map<String, TradingStrategy> strategies = new LinkedHashMap<>();
    strategies.put("AAA", new ScriptedStrategy(Map.of(0, Direction.BUY)));
    strategies.put("BBB", new NeverTradeStrategy());

    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.taker(),
            warmupBars,
            new BigDecimal("0.5"),
            null,
            null,
            8760);
    RiskManagerPort rm = fixedNotionalRm(new BigDecimal("5000")); // q = 5000/100 = 50

    BacktestResult result =
        PortfolioFundingArbHarnessV2.run(
            perpBySymbol, spotBySymbol, Map.of(), strategies, CandleInterval.ONE_HOUR, cfg, rm);

    assertThat(result.tradeCount()).isEqualTo(1);
    assertThat(result.trades().get(0).exitReason()).isEqualTo(ExitReason.LIQUIDATION_GUARD);
    assertThat(((Number) result.diagnostics().get("liquidations")).longValue()).isEqualTo(1L);
    assertThat(((Number) result.diagnostics().get("trades.BBB")).longValue()).isEqualTo(0L);

    // PLAN-019 Task A: per-event liquidation attribution (symbol, time, book context) — the
    // aggregate "liquidations" counter alone can't answer the triage's per-event questions.
    @SuppressWarnings("unchecked")
    List<LiquidationEvent> events =
        (List<LiquidationEvent>) result.diagnostics().get("liquidationEvents");
    assertThat(events).hasSize(1);
    LiquidationEvent event = events.get(0);
    assertThat(event.symbol()).isEqualTo("AAA");
    assertThat(event.perpLoss()).isGreaterThanOrEqualTo(event.marginThreshold());
    assertThat(event.overshoot())
        .isEqualByComparingTo(event.perpLoss().subtract(event.marginThreshold()));
    // BBB never traded, so the book at liquidation is AAA's notional alone.
    assertThat(event.bookNotionalAtLiquidation())
        .isEqualByComparingTo(new BigDecimal("50").multiply(rampCloses[4]));

    // "liquidationEvents" is the one diagnostics entry JsonWriter cannot render (it only accepts
    // null/String/Boolean/Number/Map/List), so every caller that serialises the whole diagnostics
    // map must remove it first -- PortfolioCli writes it to liquidations.csv instead. Without that,
    // --funding-model=v2 throws while writing result.json *after* a full backtest has run, and only
    // on runs that actually liquidated. Both halves are asserted so that adding another
    // record-typed diagnostic fails here rather than at the end of an overnight run.
    assertThatThrownBy(() -> JsonWriter.pretty(result.diagnostics()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(LiquidationEvent.class.getName());
    Map<String, Object> serialisable = new LinkedHashMap<>(result.diagnostics());
    serialisable.remove("liquidationEvents");
    assertThat(JsonWriter.pretty(serialisable)).contains("liquidations");
  }

  // ── Test 4: sub-bar realism reused correctly in the portfolio context ───────────────────────

  @Test
  void perpSubBars_catchIntraBarSpike_forOnlyTheSymbolItWasSuppliedFor() {
    int warmupBars = 5;
    List<Candle> perpAaa = new java.util.ArrayList<>(flatCandles(5, new BigDecimal("100")));
    BigDecimal[] rampCloses = {
      new BigDecimal("110"), new BigDecimal("120"), new BigDecimal("130"),
      new BigDecimal("140"), new BigDecimal("150"), new BigDecimal("160")
    };
    for (int i = 0; i < rampCloses.length; i++) {
      Instant t = T0.plus(Duration.ofHours(5 + i));
      if (i == 3) {
        // Bar index 8's own high must reflect the sub-bar spike for fixture consistency (mirrors
        // FundingArbHarnessV2Test's identical setup).
        perpAaa.add(
            new Candle(
                t,
                rampCloses[i],
                new BigDecimal("147"),
                rampCloses[i],
                rampCloses[i],
                BigDecimal.ONE));
      } else {
        perpAaa.add(
            new Candle(
                t, rampCloses[i], rampCloses[i], rampCloses[i], rampCloses[i], BigDecimal.ONE));
      }
    }
    List<Candle> spotAaa = new java.util.ArrayList<>();
    for (Candle c : perpAaa) {
      spotAaa.add(new Candle(c.openTime(), c.open(), c.high(), c.low(), c.close(), c.volume()));
    }

    Instant bar8Open = T0.plus(Duration.ofHours(8));
    List<Candle> subBarsAaa =
        List.of(
            subBar(bar8Open, 0, "140"),
            subBar(bar8Open, 20, "147"), // breaches the 145 threshold here
            subBar(bar8Open, 40, "140")); // recovers -- coarse bar-8 close never saw the spike

    Map<String, List<Candle>> perpBySymbol = Map.of("AAA", perpAaa);
    Map<String, List<Candle>> spotBySymbol = Map.of("AAA", spotAaa);
    Map<String, List<Candle>> subBarsBySymbol = Map.of("AAA", subBarsAaa);
    Map<String, TradingStrategy> strategies =
        Map.of("AAA", new ScriptedStrategy(Map.of(0, Direction.BUY)));

    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.taker(),
            warmupBars,
            new BigDecimal("0.5"),
            null,
            null,
            8760);
    RiskManagerPort rm = fixedNotionalRm(new BigDecimal("5000"));

    BacktestResult result =
        PortfolioFundingArbHarnessV2.run(
            perpBySymbol,
            spotBySymbol,
            Map.of(),
            strategies,
            subBarsBySymbol,
            CandleInterval.ONE_HOUR,
            cfg,
            rm);

    assertThat(result.tradeCount()).isEqualTo(1);
    Trade trade = result.trades().get(0);
    assertThat(trade.exitReason()).isEqualTo(ExitReason.LIQUIDATION_GUARD);
    assertThat(trade.exitTime()).isEqualTo(bar8Open); // one full bar earlier than the coarse model
  }

  // ── Test 5: data gaps skip only the affected symbol ─────────────────────────────────────────

  @Test
  void missingSpotBarForOneSymbol_skipsThatSymbolOnly_otherSymbolUnaffected() {
    List<Candle> perpAaa = List.of(candle(T0, "100"), candle(T0.plus(Duration.ofHours(1)), "100"));
    List<Candle> spotAaa = List.of(candle(T0, "100")); // missing the second bar entirely
    List<Candle> perpBbb = List.of(candle(T0, "50"), candle(T0.plus(Duration.ofHours(1)), "50"));
    List<Candle> spotBbb = List.of(candle(T0, "50"), candle(T0.plus(Duration.ofHours(1)), "50"));

    Map<String, List<Candle>> perpBySymbol = new LinkedHashMap<>();
    perpBySymbol.put("AAA", perpAaa);
    perpBySymbol.put("BBB", perpBbb);
    Map<String, List<Candle>> spotBySymbol = new LinkedHashMap<>();
    spotBySymbol.put("AAA", spotAaa);
    spotBySymbol.put("BBB", spotBbb);
    Map<String, TradingStrategy> strategies = new LinkedHashMap<>();
    strategies.put("AAA", new NeverTradeStrategy());
    strategies.put("BBB", new NeverTradeStrategy());

    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 1, new BigDecimal("1.0"), null, null, 8760);
    RiskManagerPort rm = fixedNotionalRm(new BigDecimal("1"));

    BacktestResult result =
        PortfolioFundingArbHarnessV2.run(
            perpBySymbol, spotBySymbol, Map.of(), strategies, CandleInterval.ONE_HOUR, cfg, rm);

    assertThat(((Number) result.diagnostics().get("barsSkipped.AAA")).longValue()).isEqualTo(1L);
    assertThat(((Number) result.diagnostics().get("barsSkipped.BBB")).longValue()).isEqualTo(0L);
  }

  // ── Test 6: determinism ──────────────────────────────────────────────────────────────────────

  @Test
  void determinism_sameInputsProduceIdenticalResults() {
    Map<String, List<Candle>> perpBySymbol = new LinkedHashMap<>();
    perpBySymbol.put("AAA", series(30, 100.0, 0.05));
    perpBySymbol.put("BBB", series(30, 50.0, 0.03));
    Map<String, List<Candle>> spotBySymbol = new LinkedHashMap<>();
    spotBySymbol.put("AAA", series(30, 100.0, 0.05));
    spotBySymbol.put("BBB", series(30, 50.0, 0.03));

    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.binanceDefault(),
            5,
            new BigDecimal("1.0"),
            null,
            null,
            8760);
    RiskManagerPort rm = fixedNotionalRm(new BigDecimal("500"));

    Map<String, TradingStrategy> strategies1 = new LinkedHashMap<>();
    strategies1.put("AAA", new BuyOnceStrategy());
    strategies1.put("BBB", new BuyOnceStrategy());
    BacktestResult r1 =
        PortfolioFundingArbHarnessV2.run(
            perpBySymbol, spotBySymbol, Map.of(), strategies1, CandleInterval.ONE_HOUR, cfg, rm);

    Map<String, TradingStrategy> strategies2 = new LinkedHashMap<>();
    strategies2.put("AAA", new BuyOnceStrategy());
    strategies2.put("BBB", new BuyOnceStrategy());
    BacktestResult r2 =
        PortfolioFundingArbHarnessV2.run(
            perpBySymbol, spotBySymbol, Map.of(), strategies2, CandleInterval.ONE_HOUR, cfg, rm);

    assertThat(r1).isEqualTo(r2);
  }

  // ── PLAN-015 Task C: premium-index nowcast window is time-gated (no lookahead) ─────────────────

  @Test
  void premiumIndexWindow_onlyExposesEventsNotAfterCurrentBarOpenTime() {
    Map<String, List<Candle>> perpBySymbol = new LinkedHashMap<>();
    perpBySymbol.put("AAA", flatCandles(6, new BigDecimal("100")));
    Map<String, List<Candle>> spotBySymbol = new LinkedHashMap<>();
    spotBySymbol.put("AAA", flatCandles(6, new BigDecimal("100")));

    List<PremiumIndexEvent> premiumIndex =
        List.of(
            new PremiumIndexEvent(T0.plus(Duration.ofHours(1)), new BigDecimal("0.0001")),
            new PremiumIndexEvent(T0.plus(Duration.ofHours(2)), new BigDecimal("0.0002")),
            new PremiumIndexEvent(T0.plus(Duration.ofHours(3)), new BigDecimal("0.0003")));
    Map<String, List<PremiumIndexEvent>> premiumIndexBySymbol = new LinkedHashMap<>();
    premiumIndexBySymbol.put("AAA", premiumIndex);

    RecordingStrategy strategy = new RecordingStrategy();
    Map<String, TradingStrategy> strategies = new LinkedHashMap<>();
    strategies.put("AAA", strategy);

    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.binanceDefault(),
            3,
            new BigDecimal("1.0"),
            null,
            null,
            8760);
    RiskManagerPort rm = fixedNotionalRm(new BigDecimal("500"));

    PortfolioFundingArbHarnessV2.run(
        perpBySymbol,
        spotBySymbol,
        Map.of(),
        premiumIndexBySymbol,
        strategies,
        Map.of(),
        CandleInterval.ONE_HOUR,
        cfg,
        rm);

    // 6 bars, warmupBars=3 ⇒ the window first fills at bar 2 and evaluates on bars 2..5.
    assertThat(strategy.seenPremiumIndexHistories).hasSize(4);
    // Eval 0 (asOf = T0+2h): the T0+3h sample must not be visible yet.
    assertThat(strategy.seenPremiumIndexHistories.get(0))
        .extracting(PremiumIndexEvent::time)
        .containsExactly(T0.plus(Duration.ofHours(1)), T0.plus(Duration.ofHours(2)));
    // Eval 1 (asOf = T0+3h): now visible.
    assertThat(strategy.seenPremiumIndexHistories.get(1))
        .extracting(PremiumIndexEvent::time)
        .containsExactly(
            T0.plus(Duration.ofHours(1)),
            T0.plus(Duration.ofHours(2)),
            T0.plus(Duration.ofHours(3)));
  }

  @Test
  void premiumIndexWindow_denselySampledSeriesIsCutAtTheBarBoundaryAndCapped() {
    // The dense regime the field exists for: 1-minute samples against 1-hour bars. Also exercises
    // the per-symbol window cap (see FundingArbHarnessV2#PREMIUM_INDEX_WINDOW_MAX_SAMPLES) — an
    // unbounded window here is quadratic per symbol.
    Map<String, List<Candle>> perpBySymbol = new LinkedHashMap<>();
    perpBySymbol.put("AAA", flatCandles(6, new BigDecimal("100")));
    Map<String, List<Candle>> spotBySymbol = new LinkedHashMap<>();
    spotBySymbol.put("AAA", flatCandles(6, new BigDecimal("100")));

    List<PremiumIndexEvent> premiumIndex = new ArrayList<>();
    for (int m = 1; m <= 5 * 60; m++) {
      premiumIndex.add(
          new PremiumIndexEvent(T0.plus(Duration.ofMinutes(m)), new BigDecimal("0.0001")));
    }
    Map<String, List<PremiumIndexEvent>> premiumIndexBySymbol = new LinkedHashMap<>();
    premiumIndexBySymbol.put("AAA", premiumIndex);

    RecordingStrategy strategy = new RecordingStrategy();
    Map<String, TradingStrategy> strategies = new LinkedHashMap<>();
    strategies.put("AAA", strategy);

    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.binanceDefault(),
            3,
            new BigDecimal("1.0"),
            null,
            null,
            8760);

    PortfolioFundingArbHarnessV2.run(
        perpBySymbol,
        spotBySymbol,
        Map.of(),
        premiumIndexBySymbol,
        strategies,
        Map.of(),
        CandleInterval.ONE_HOUR,
        cfg,
        fixedNotionalRm(new BigDecimal("500")));

    // Eval 0 is the bar opening at T0+2h: everything up to and including T0+2h exactly, nothing
    // after — T0+2h01m belongs to a bar the strategy has not been shown yet.
    List<PremiumIndexEvent> firstEval = strategy.seenPremiumIndexHistories.get(0);
    assertThat(firstEval).hasSize(120); // T0+1m .. T0+2h00m
    assertThat(firstEval.getLast().time()).isEqualTo(T0.plus(Duration.ofHours(2)));
    assertThat(firstEval)
        .extracting(PremiumIndexEvent::time)
        .doesNotContain(T0.plus(Duration.ofMinutes(121)));
    // Every window stays within the cap.
    assertThat(strategy.seenPremiumIndexHistories)
        .allSatisfy(
            seen ->
                assertThat(seen.size())
                    .isLessThanOrEqualTo(FundingArbHarnessV2.PREMIUM_INDEX_WINDOW_MAX_SAMPLES));
  }

  @Test
  void premiumIndexSeriesIsValidatedPerSymbolBeforeAnyBarIsWalked() {
    Map<String, List<Candle>> perpBySymbol = new LinkedHashMap<>();
    perpBySymbol.put("AAA", flatCandles(6, new BigDecimal("100")));
    Map<String, List<Candle>> spotBySymbol = new LinkedHashMap<>();
    spotBySymbol.put("AAA", flatCandles(6, new BigDecimal("100")));
    Map<String, TradingStrategy> strategies = new LinkedHashMap<>();
    strategies.put("AAA", new RecordingStrategy());

    // 4h cadence against 1h bars — each sample carries its kline's close, so it would only be
    // observable well after the bar it is shown to.
    Map<String, List<PremiumIndexEvent>> premiumIndexBySymbol = new LinkedHashMap<>();
    premiumIndexBySymbol.put(
        "AAA",
        List.of(
            new PremiumIndexEvent(T0, new BigDecimal("0.0001")),
            new PremiumIndexEvent(T0.plus(Duration.ofHours(4)), new BigDecimal("0.0002"))));

    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.binanceDefault(),
            3,
            new BigDecimal("1.0"),
            null,
            null,
            8760);

    assertThatThrownBy(
            () ->
                PortfolioFundingArbHarnessV2.run(
                    perpBySymbol,
                    spotBySymbol,
                    Map.of(),
                    premiumIndexBySymbol,
                    strategies,
                    Map.of(),
                    CandleInterval.ONE_HOUR,
                    cfg,
                    fixedNotionalRm(new BigDecimal("500"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("AAA")
        .hasMessageContaining("lookahead");
  }

  /** Records each evaluation's {@code premiumIndexHistory} snapshot; never signals. */
  private static final class RecordingStrategy implements TradingStrategy {
    final List<List<PremiumIndexEvent>> seenPremiumIndexHistories = new ArrayList<>();

    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
      seenPremiumIndexHistories.add(List.copyOf(ctx.premiumIndexHistory()));
      return Optional.empty();
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("Recording", "0.0.0", "test-only");
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

  /** A fixed-notional RM: approves every BUY at exactly {@code notional}, always a $1 stop. */
  private static RiskManagerPort fixedNotionalRm(BigDecimal notional) {
    return new RiskManagerPort() {
      @Override
      public ExecutionDecision gate(
          TechnicalSignal signal, PortfolioState state, MarketContext ctx) {
        if (signal.direction() == Direction.HOLD) {
          return new ExecutionDecision.Refuse(
              signal.symbol(), ExecutionDecision.RefusalReason.HOLD_SIGNAL, "hold", signal.asOf());
        }
        BigDecimal markPrice = ctx.candles().getLast().close();
        if (signal.direction() == Direction.SELL) {
          BigDecimal size = notional.divide(markPrice, IndicatorMath.MC);
          return new ExecutionDecision.Execute(
              signal.symbol(),
              Direction.SELL,
              size,
              notional,
              markPrice.add(BigDecimal.ONE),
              BigDecimal.ONE,
              Optional.empty(),
              "stub fixed-notional sell",
              List.of(),
              signal.asOf());
        }
        BigDecimal size = notional.divide(markPrice, IndicatorMath.MC);
        return new ExecutionDecision.Execute(
            signal.symbol(),
            Direction.BUY,
            size,
            notional,
            markPrice.subtract(BigDecimal.ONE),
            BigDecimal.ONE,
            Optional.empty(),
            "stub fixed-notional buy",
            List.of(),
            signal.asOf());
      }

      @Override
      public RiskParameters riskParameters() {
        return RiskParameters.defaults();
      }
    };
  }

  private static Candle candle(Instant t, String price) {
    BigDecimal p = new BigDecimal(price);
    return new Candle(t, p, p, p, p, BigDecimal.ONE);
  }

  private static List<Candle> flatCandles(int n, BigDecimal price) {
    List<Candle> out = new java.util.ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      out.add(new Candle(T0.plus(Duration.ofHours(i)), price, price, price, price, BigDecimal.ONE));
    }
    return out;
  }

  private static List<Candle> series(int count, double startClose, double step) {
    List<Candle> out = new java.util.ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      double close = startClose + i * step;
      BigDecimal p = BigDecimal.valueOf(close);
      out.add(new Candle(T0.plus(Duration.ofHours(i)), p, p, p, p, BigDecimal.ONE));
    }
    return out;
  }

  private static Candle subBar(Instant parentOpen, long minuteOffset, String price) {
    BigDecimal p = new BigDecimal(price);
    return new Candle(
        parentOpen.plus(Duration.ofMinutes(minuteOffset)), p, p, p, p, BigDecimal.ONE);
  }
}
