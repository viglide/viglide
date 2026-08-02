package app.viglide.core.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.spi.StrategyKind;
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
 * Deterministic fixture tests for {@link FundingArbHarnessV2} (PLAN-008 Task F): the two-leg cash
 * arithmetic, the liquidation guard, and min-hold. Uses a {@link ScriptedStrategy} test double so
 * entry/exit timing is fully controlled — independent of {@link
 * app.viglide.strategies.fundingarb.FundingArbStrategy}'s own threshold logic.
 */
class FundingArbHarnessV2Test {

  private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");
  private static final String SYMBOL = "BTCUSDT";

  // ── Test 1: flat prices + constant positive funding ────────────────────────────────────────

  @Test
  void flatPricesConstantFunding_equityGrowsByFundingSumMinusFourLegFees() {
    int warmupBars = 5;
    List<Candle> perp = flatCandles(20, new BigDecimal("100"));
    List<Candle> spot = flatCandles(20, new BigDecimal("100"));

    // BUY at the first evaluation (bar index 4); SELL 11 evaluations later (bar index 15) — well
    // after all three funding events have accrued. Flat prices ⇒ zero perp P&L, zero spot P&L.
    ScriptedStrategy strategy = new ScriptedStrategy(Map.of(0, Direction.BUY, 11, Direction.SELL));

    List<FundingEvent> funding =
        List.of(
            new FundingEvent(perp.get(6).openTime(), new BigDecimal("0.001")),
            new FundingEvent(perp.get(8).openTime(), new BigDecimal("0.001")),
            new FundingEvent(perp.get(10).openTime(), new BigDecimal("0.001")));

    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.taker(), // 5bps fee + 5bps slippage = 0.001 total adverse factor per leg
            warmupBars,
            new BigDecimal("0.5"),
            null,
            null,
            8760);

    BacktestResult result =
        FundingArbHarnessV2.run(
            strategy, perp, spot, funding, SYMBOL, CandleInterval.ONE_HOUR, cfg);

    // q = notional/spotClose = (10000 * 0.5) / 100 = 50.
    // Each leg fee = q * price * 0.001 = 50 * 100 * 0.001 = 5; four legs (spot+perp, entry+exit) =
    // 20.
    // Funding = 3 events * (q * rate * perpMark) = 3 * (50 * 0.001 * 100) = 15.
    BigDecimal expectedEquity =
        new BigDecimal("10000").add(new BigDecimal("15")).subtract(new BigDecimal("20"));
    assertThat(result.endingEquity()).isEqualByComparingTo(expectedEquity);
    assertThat(result.tradeCount()).isEqualTo(1);
    assertThat(result.trades().get(0).exitReason()).isEqualTo(ExitReason.SIGNAL);

    // PLAN-008 Task I: the fee/funding split diagnostics must reproduce the same two figures the
    // equity assertion above was hand-derived from -- 15 collected, 20 paid across all four legs.
    assertThat((BigDecimal) result.diagnostics().get("netFundingAccrued"))
        .isEqualByComparingTo(new BigDecimal("15"));
    assertThat((BigDecimal) result.diagnostics().get("totalFeesPaid"))
        .isEqualByComparingTo(new BigDecimal("20"));
  }

  // ── Test 2: liquidation guard ────────────────────────────────────────────────────────────────

  @Test
  void perpPump_liquidationGuardClosesBeforeFullMarginLoss() {
    int warmupBars = 5;
    // Entry bar (index 4) at 100, then ramps +10/bar: 110,120,...,160 (a +60% pump by the end).
    List<Candle> perp = new ArrayList<>(flatCandles(5, new BigDecimal("100")));
    BigDecimal[] rampCloses = {
      new BigDecimal("110"), new BigDecimal("120"), new BigDecimal("130"),
      new BigDecimal("140"), new BigDecimal("150"), new BigDecimal("160")
    };
    for (int i = 0; i < rampCloses.length; i++) {
      Instant t = T0.plus(Duration.ofHours(5 + i));
      perp.add(
          new Candle(
              t, rampCloses[i], rampCloses[i], rampCloses[i], rampCloses[i], BigDecimal.ONE));
    }
    // Spot tracks perp exactly — the spot leg is neutral; only the perp leg drives liquidation.
    List<Candle> spot = new ArrayList<>();
    for (Candle c : perp) {
      spot.add(new Candle(c.openTime(), c.open(), c.high(), c.low(), c.close(), c.volume()));
    }

    ScriptedStrategy strategy = new ScriptedStrategy(Map.of(0, Direction.BUY));

    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.taker(),
            warmupBars,
            new BigDecimal("0.5"),
            null,
            null,
            8760);

    // No RM ⇒ default leverage 2.0. margin = q*entry/2; 90% buffer trips at perpClose >=
    // entry*(1+0.9/2) = 145. First ramp close reaching/exceeding that is 150 (bar index 9),
    // strictly before the ramp's final +60% (160, bar index 10) is ever reached.
    BacktestResult result =
        FundingArbHarnessV2.run(
            strategy, perp, spot, List.of(), SYMBOL, CandleInterval.ONE_HOUR, cfg);

    assertThat(result.tradeCount()).isEqualTo(1);
    Trade trade = result.trades().get(0);
    assertThat(trade.exitReason()).isEqualTo(ExitReason.LIQUIDATION_GUARD);
    assertThat(trade.exitPrice()).isEqualByComparingTo("150");
    assertThat(((Number) result.diagnostics().get("liquidations")).longValue()).isEqualTo(1L);
  }

  // ── PLAN-009 Task B2: sub-bar-aware liquidation guard ───────────────────────────────────────

  @Test
  void perpSubBars_catchIntraBarSpikeThatRecoversBeforeDecisionBarClose() {
    // Same ramp as perpPump_liquidationGuardClosesBeforeFullMarginLoss (entry 100, margin=2500,
    // 90%-buffer threshold at perpClose >= 145), except bar index 8's own close (140) never trips
    // the coarse close-only check -- but a 1m-style spike to 147 *inside* that hour, recovering to
    // 140 by the hour's own close, would have breached the buffer in real life. The sub-bar-aware
    // guard must catch it one full decision bar earlier than the coarse model (index 8, not 9).
    int warmupBars = 5;
    List<Candle> perp = new ArrayList<>(flatCandles(5, new BigDecimal("100")));
    BigDecimal[] rampCloses = {
      new BigDecimal("110"), new BigDecimal("120"), new BigDecimal("130"),
      new BigDecimal("140"), new BigDecimal("150"), new BigDecimal("160")
    };
    for (int i = 0; i < rampCloses.length; i++) {
      Instant t = T0.plus(Duration.ofHours(5 + i));
      if (i == 3) {
        // Bar index 8 (close=140): its own *high* must reflect the sub-bar spike below (147) for
        // the fixture to be internally consistent -- a real aggregation's parent high is always
        // >= every sub-bar's price. Only the close stays at 140, so the coarse close-only check
        // still misses the danger this bar's high already reveals happened.
        perp.add(
            new Candle(
                t,
                rampCloses[i],
                new BigDecimal("147"),
                rampCloses[i],
                rampCloses[i],
                BigDecimal.ONE));
      } else {
        perp.add(
            new Candle(
                t, rampCloses[i], rampCloses[i], rampCloses[i], rampCloses[i], BigDecimal.ONE));
      }
    }
    List<Candle> spot = new ArrayList<>();
    for (Candle c : perp) {
      spot.add(new Candle(c.openTime(), c.open(), c.high(), c.low(), c.close(), c.volume()));
    }

    // Bar index 8 (T0+8h, close=140, the bar right before the coarse model's bar-9 trip) gets
    // three 1m-style sub-bars: starts at 140, spikes to 147 (breaches the 145 threshold), then
    // recovers to 140 by the sub-bar-implied hour close -- invisible to a close-only check.
    Instant bar8Open = T0.plus(Duration.ofHours(8));
    List<Candle> perpSubBars =
        List.of(
            sub(bar8Open, 0, "140"),
            sub(bar8Open, 20, "147"), // breaches liquidation threshold here
            sub(bar8Open, 40, "140")); // recovers -- coarse bar-8 close never saw the spike

    ScriptedStrategy strategy = new ScriptedStrategy(Map.of(0, Direction.BUY));
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.taker(),
            warmupBars,
            new BigDecimal("0.5"),
            null,
            null,
            8760);

    BacktestResult result =
        FundingArbHarnessV2.run(
            strategy,
            perp,
            spot,
            List.of(),
            SYMBOL,
            CandleInterval.ONE_HOUR,
            cfg,
            Optional.empty(),
            perpSubBars);

    assertThat(result.tradeCount()).isEqualTo(1);
    Trade trade = result.trades().get(0);
    assertThat(trade.exitReason()).isEqualTo(ExitReason.LIQUIDATION_GUARD);
    // Trade.exitPrice() records the SPOT leg's close (Trade's entry/exit prices are always the
    // spot side of the basis trade — see close()'s Javadoc), not the perp price that actually
    // triggered the guard; this test has no spot sub-bars, so it stays at bar 8's own spot close.
    // The real proof the sub-bar mechanism engaged, not the coarse close-only check, is *when* the
    // liquidation fired: bar 8's own perp close (140) never clears the 2250 threshold on its own
    // (perpLoss = (140-100)*50 = 2000) -- only the 147 sub-bar does -- so exitTime landing on bar
    // 8 (not bar 9, where the coarse model trips) is only possible if 147 was actually used.
    assertThat(trade.exitPrice()).isEqualByComparingTo("140");
    assertThat(trade.exitTime()).isEqualTo(bar8Open);
  }

  @Test
  void perpSubBars_closeOutsideParentRange_failsLoudly() {
    // Data-integrity guard, mirroring VirtualExchangeTest's equivalent: a sub-bar close of 9999
    // cannot have aggregated into a parent bar whose own [low, high] never reached that far —
    // fail loudly rather than let a mismatched pair/period silently mistime a liquidation.
    int warmupBars = 5;
    List<Candle> perp = flatCandles(6, new BigDecimal("100"));
    List<Candle> spot = flatCandles(6, new BigDecimal("100"));
    ScriptedStrategy strategy = new ScriptedStrategy(Map.of(0, Direction.BUY));
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.taker(),
            warmupBars,
            new BigDecimal("0.5"),
            null,
            null,
            8760);
    List<Candle> corruptSubBars = List.of(sub(perp.get(4).openTime(), 0, "9999"));

    assertThatThrownBy(
            () ->
                FundingArbHarnessV2.run(
                    strategy,
                    perp,
                    spot,
                    List.of(),
                    SYMBOL,
                    CandleInterval.ONE_HOUR,
                    cfg,
                    Optional.empty(),
                    corruptSubBars))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("out of parent bar's range");
  }

  @Test
  void perpSubBars_outOfChronologicalOrder_failsLoudly() {
    // PLAN-011 Task E / F5: both individually price-legal and time-window-legal, but reversed.
    int warmupBars = 5;
    List<Candle> perp = flatCandles(6, new BigDecimal("100"));
    List<Candle> spot = flatCandles(6, new BigDecimal("100"));
    ScriptedStrategy strategy = new ScriptedStrategy(Map.of(0, Direction.BUY));
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.taker(),
            warmupBars,
            new BigDecimal("0.5"),
            null,
            null,
            8760);
    Instant parentOpen = perp.get(4).openTime();
    List<Candle> outOfOrderSubBars = List.of(sub(parentOpen, 20, "100"), sub(parentOpen, 0, "100"));

    assertThatThrownBy(
            () ->
                FundingArbHarnessV2.run(
                    strategy,
                    perp,
                    spot,
                    List.of(),
                    SYMBOL,
                    CandleInterval.ONE_HOUR,
                    cfg,
                    Optional.empty(),
                    outOfOrderSubBars))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not strictly ascending");
  }

  @Test
  void perpSubBars_openBeforeParentOpen_failsLoudly() {
    // PLAN-011 Task E / F5: the harness's monotonic cursor cannot itself produce an out-of-window
    // slice element from a properly *sorted* input (by construction -- see VirtualExchangeTest's
    // equivalent for the direct, cursor-free version of this check). To exercise this guard clause
    // through the one real entry point (run()), the raw sub-bar list must itself be unsorted: the
    // cursor's first while-loop only stops skipping once it meets an element that is NOT before the
    // current bar's openTime, so an early, in-window element (T0+10m) stops that skip early and
    // lets
    // a later, chronologically-EARLIER element (T0-30m) get swept into the same slice by the second
    // (greedy, order-blind) while-loop. A real 1m-data file arriving out of order would reproduce
    // exactly this shape.
    int warmupBars = 5;
    List<Candle> perp = flatCandles(6, new BigDecimal("100"));
    List<Candle> spot = flatCandles(6, new BigDecimal("100"));
    ScriptedStrategy strategy = new ScriptedStrategy(Map.of(0, Direction.BUY));
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.taker(),
            warmupBars,
            new BigDecimal("0.5"),
            null,
            null,
            8760);
    Instant bar0Open = perp.get(0).openTime();
    List<Candle> unsortedSubBars =
        List.of(
            sub(bar0Open, 10, "100"), // in-window; stops the cursor's initial skip-loop early
            sub(bar0Open, -30, "100")); // chronologically before bar0Open, but swept in anyway

    assertThatThrownBy(
            () ->
                FundingArbHarnessV2.run(
                    strategy,
                    perp,
                    spot,
                    List.of(),
                    SYMBOL,
                    CandleInterval.ONE_HOUR,
                    cfg,
                    Optional.empty(),
                    unsortedSubBars))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not lie within its parent bar's time window");
  }

  // ── Test 3: min-hold ─────────────────────────────────────────────────────────────────────────

  @Test
  void minHoldBars_ignoresEarlySellButHonoursLaterOne() {
    int warmupBars = 5;
    // Enough bars for an evaluation 50 ticks after entry (barIndex 4+50=54 ⇒ 55 candles).
    List<Candle> perp = flatCandles(55, new BigDecimal("100"));
    List<Candle> spot = flatCandles(55, new BigDecimal("100"));

    // BUY at evaluation 0 (bar 4). SELL at evaluation 10 (bar 14, only 10 bars held — must be
    // ignored under minHoldBars=48). SELL again at evaluation 50 (bar 54, 50 bars held — honoured).
    ScriptedStrategy strategy =
        new ScriptedStrategy(Map.of(0, Direction.BUY, 10, Direction.SELL, 50, Direction.SELL));

    BacktestConfig baseCfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.taker(),
            warmupBars,
            new BigDecimal("0.5"),
            null,
            null,
            8760);
    BacktestConfig cfg =
        new BacktestConfig(
            baseCfg.startingCash(),
            baseCfg.fees(),
            baseCfg.warmupBars(),
            baseCfg.maxPositionPct(),
            baseCfg.stopLossPct(),
            baseCfg.takeProfitPct(),
            baseCfg.barsPerYear(),
            48);

    BacktestResult result =
        FundingArbHarnessV2.run(
            strategy, perp, spot, List.of(), SYMBOL, CandleInterval.ONE_HOUR, cfg);

    assertThat(result.tradeCount()).isEqualTo(1); // the bar-14 SELL never produced a trade
    Trade trade = result.trades().get(0);
    assertThat(Duration.between(trade.entryTime(), trade.exitTime()))
        .isEqualTo(Duration.ofHours(50));
  }

  // ── PLAN-015 Task C: premium-index nowcast window is time-gated (no lookahead) ─────────────────

  @Test
  void premiumIndexWindow_onlyExposesEventsNotAfterCurrentBarOpenTime() {
    int warmupBars = 3;
    List<Candle> perp = flatCandles(6, new BigDecimal("100"));
    List<Candle> spot = flatCandles(6, new BigDecimal("100"));

    // First evaluation is bar index 2 (openTime T0+2h); second is bar index 3 (T0+3h).
    List<app.viglide.core.domain.PremiumIndexEvent> premiumIndex =
        List.of(
            new app.viglide.core.domain.PremiumIndexEvent(
                T0.plus(Duration.ofHours(1)), new BigDecimal("0.0001")),
            new app.viglide.core.domain.PremiumIndexEvent(
                T0.plus(Duration.ofHours(2)), new BigDecimal("0.0002")),
            new app.viglide.core.domain.PremiumIndexEvent(
                T0.plus(Duration.ofHours(3)), new BigDecimal("0.0003")));

    RecordingStrategy strategy = new RecordingStrategy();
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.taker(),
            warmupBars,
            new BigDecimal("0.5"),
            null,
            null,
            8760);

    FundingArbHarnessV2.run(
        strategy,
        perp,
        spot,
        List.of(),
        premiumIndex,
        SYMBOL,
        CandleInterval.ONE_HOUR,
        cfg,
        Optional.empty(),
        List.of());

    assertThat(strategy.seenPremiumIndexHistories).hasSizeGreaterThanOrEqualTo(2);
    // Eval 0 (asOf = T0+2h): the T0+3h sample must not be visible yet.
    assertThat(strategy.seenPremiumIndexHistories.get(0))
        .extracting(app.viglide.core.domain.PremiumIndexEvent::time)
        .containsExactly(T0.plus(Duration.ofHours(1)), T0.plus(Duration.ofHours(2)));
    // Eval 1 (asOf = T0+3h): now visible.
    assertThat(strategy.seenPremiumIndexHistories.get(1))
        .extracting(app.viglide.core.domain.PremiumIndexEvent::time)
        .containsExactly(
            T0.plus(Duration.ofHours(1)),
            T0.plus(Duration.ofHours(2)),
            T0.plus(Duration.ofHours(3)));
  }

  /** Records each evaluation's {@code premiumIndexHistory} snapshot; never signals. */
  private static final class RecordingStrategy implements TradingStrategy {
    final List<List<app.viglide.core.domain.PremiumIndexEvent>> seenPremiumIndexHistories =
        new ArrayList<>();

    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
      seenPremiumIndexHistories.add(List.copyOf(ctx.premiumIndexHistory()));
      return Optional.empty();
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("Recording", "0.0.0", "test double", StrategyKind.FUNDING_AWARE);
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

  private static List<Candle> flatCandles(int n, BigDecimal price) {
    List<Candle> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      Instant t = T0.plus(Duration.ofHours(i));
      out.add(new Candle(t, price, price, price, price, BigDecimal.ONE));
    }
    return out;
  }

  /**
   * A 1m-style sub-bar {@code minuteOffset} minutes into the decision bar opening at {@code
   * parentOpen}, flat OHLC at {@code price} (PLAN-009 Task B2).
   */
  private static Candle sub(Instant parentOpen, long minuteOffset, String price) {
    BigDecimal p = new BigDecimal(price);
    return new Candle(
        parentOpen.plus(Duration.ofMinutes(minuteOffset)), p, p, p, p, BigDecimal.ONE);
  }

  /** Emits a scripted Direction on the Nth {@code evaluate()} call (0-indexed); HOLD otherwise. */
  private static final class ScriptedStrategy implements TradingStrategy {
    private final Map<Integer, Direction> script;
    private int callCount = 0;

    ScriptedStrategy(Map<Integer, Direction> script) {
      this.script = new LinkedHashMap<>(script);
    }

    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
      Direction d = script.get(callCount);
      callCount++;
      if (d == null) return Optional.empty();
      return Optional.of(
          new TechnicalSignal(ctx.symbol(), d, 1.0, List.of(), "scripted", ctx.asOf()));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("Scripted", "0.0.0", "test double", StrategyKind.FUNDING_AWARE);
    }
  }
}
