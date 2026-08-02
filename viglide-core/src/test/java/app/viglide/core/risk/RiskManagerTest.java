package app.viglide.core.risk;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.ExchangeFilters;
import app.viglide.core.domain.Factor;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.indicator.IndicatorMath;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.Scale;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RiskManager} — one test per hard limit from CLAUDE.md §7. */
class RiskManagerTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final RiskParameters DEFAULT_PARAMS = RiskParameters.defaults();
  // Clock fixed at T0: every signal built by buySignal()/sellSignal() below is dated T0, so this
  // shared RM never trips the F7 staleness check by coincidence of wall-clock drift.
  private static final RiskManager RM =
      new RiskManager(DEFAULT_PARAMS, Clock.fixed(T0, ZoneOffset.UTC));

  // ── helper builders ─────────────────────────────────────────────────────────────────────────

  /** Builds a flat candle series (price = 50,000, volume = 1,000) with 100-bar warmup. */
  private static List<Candle> flatCandles(int count) {
    List<Candle> out = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      out.add(
          new Candle(
              T0.plusSeconds(3600L * i),
              bd("50000"),
              bd("50100"),
              bd("49900"),
              bd("50000"),
              bd("1000"))); // 1,000 BTC daily volume → 24 × 1,000 = 24,000 per day
    }
    return out;
  }

  private static TechnicalSignal buySignal(double confidence) {
    return new TechnicalSignal(
        "BTCUSDT",
        Direction.BUY,
        confidence,
        List.of(new Factor("TEST", "test", 1.0)),
        "test buy",
        T0);
  }

  private static TechnicalSignal sellSignal(double confidence) {
    return new TechnicalSignal(
        "BTCUSDT",
        Direction.SELL,
        confidence,
        List.of(new Factor("TEST", "test", 1.0)),
        "test sell",
        T0);
  }

  private static PortfolioState freshState(BigDecimal equity) {
    return new PortfolioState(equity, equity, equity, Map.of(), false, Optional.empty());
  }

  /** Like {@link #freshState} but also seeds a UTC-day-start equity (PLAN-012 Task F). */
  private static PortfolioState freshStateWithDayStart(BigDecimal equity, BigDecimal dayStart) {
    return new PortfolioState(equity, equity, equity, Map.of(), false, Optional.of(dayStart));
  }

  private static MarketContext ctx(List<Candle> candles) {
    return new MarketContext("BTCUSDT", CandleInterval.ONE_HOUR, candles);
  }

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  // ── 1. HOLD signal is refused ────────────────────────────────────────────────────────────────

  @Test
  void holdSignal_isRefused() {
    var signal =
        new TechnicalSignal(
            "BTCUSDT", Direction.HOLD, 0.8, List.of(new Factor("X", "x", 1.0)), "hold", T0);
    var decision = RM.gate(signal, freshState(bd("10000")), ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Refuse.class);
    assertThat(((ExecutionDecision.Refuse) decision).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.HOLD_SIGNAL);
  }

  // ── 2. Circuit breaker ──────────────────────────────────────────────────────────────────────

  @Test
  void circuitBreakerTripped_refusesAllSignals() {
    var trippedState =
        new PortfolioState(bd("8000"), bd("8000"), bd("10000"), Map.of(), true, Optional.empty());
    var decision = RM.gate(buySignal(0.8), trippedState, ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Refuse.class);
    assertThat(((ExecutionDecision.Refuse) decision).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.CIRCUIT_BREAKER_TRIPPED);
  }

  // ── 3. Confidence floor ─────────────────────────────────────────────────────────────────────

  @Test
  void confidenceBelowFloor_isRefused() {
    // Default floor is 0.5; signal at 0.49 should be refused.
    var decision = RM.gate(buySignal(0.49), freshState(bd("10000")), ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Refuse.class);
    assertThat(((ExecutionDecision.Refuse) decision).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.CONFIDENCE_BELOW_THRESHOLD);
  }

  @Test
  void confidenceAtFloor_isAllowed() {
    var decision = RM.gate(buySignal(0.5), freshState(bd("10000")), ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
  }

  // ── 4. Stale input (too few candles for ATR) ────────────────────────────────────────────────

  @Test
  void tooFewCandles_refusesWithStaleInput() {
    var decision = RM.gate(buySignal(0.8), freshState(bd("10000")), ctx(flatCandles(10)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Refuse.class);
    assertThat(((ExecutionDecision.Refuse) decision).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.STALE_INPUT);
  }

  // ── 5. Happy path ────────────────────────────────────────────────────────────────────────────

  @Test
  void happyPath_producesExecuteWithPositiveSizeAndStopLoss() {
    var decision = RM.gate(buySignal(0.8), freshState(bd("10000")), ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
    var exec = (ExecutionDecision.Execute) decision;
    assertThat(exec.side()).isEqualTo(Direction.BUY);
    assertThat(exec.size().signum()).isPositive();
    assertThat(exec.stopLoss().signum()).isPositive();
    // Stop-loss must be below entry for BUY.
    assertThat(exec.stopLoss().compareTo(bd("50000"))).isNegative();
    // Size must be ≤ 2% of equity / entry price = 0.02 * 10,000 / 50,000 = 0.004 BTC.
    assertThat(exec.size().compareTo(bd("0.004"))).isLessThanOrEqualTo(0);
  }

  @Test
  void sellSignal_stopLossAboveEntry() {
    // F5: SELL now requires an existing long position to close, so gate against a non-flat state
    // (a fresh/flat state would now be refused with NO_OPEN_POSITION_TO_CLOSE — see
    // sellWhileFlat_refusesWithNoOpenPositionToClose below).
    var stateWithPosition =
        new PortfolioState(
            bd("5000"),
            bd("10000"),
            bd("10000"),
            Map.of("BTCUSDT", bd("5000")),
            false,
            Optional.empty());
    var decision = RM.gate(sellSignal(0.8), stateWithPosition, ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
    var exec = (ExecutionDecision.Execute) decision;
    // For a SELL (closing a long), stop-loss must be above entry price.
    assertThat(exec.stopLoss().compareTo(bd("50000"))).isPositive();
  }

  // ── F5. SELL while flat ─────────────────────────────────────────────────────────────────────

  @Test
  void sellWhileFlat_refusesWithNoOpenPositionToClose() {
    var decision = RM.gate(sellSignal(0.8), freshState(bd("10000")), ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Refuse.class);
    assertThat(((ExecutionDecision.Refuse) decision).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.NO_OPEN_POSITION_TO_CLOSE);
  }

  // ── F4. Zero/unavailable daily volume fails safe ────────────────────────────────────────────

  @Test
  void zeroVolumeCandles_refusesWithStaleInput() {
    List<Candle> zeroVolumeCandles = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      zeroVolumeCandles.add(
          new Candle(
              T0.plusSeconds(3600L * i),
              bd("50000"),
              bd("50100"),
              bd("49900"),
              bd("50000"),
              bd("0")));
    }
    var decision = RM.gate(buySignal(0.8), freshState(bd("10000")), ctx(zeroVolumeCandles));
    assertThat(decision).isInstanceOf(ExecutionDecision.Refuse.class);
    assertThat(((ExecutionDecision.Refuse) decision).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.STALE_INPUT);
  }

  // ── F7. Signal staleness ─────────────────────────────────────────────────────────────────────

  @Test
  void signalOlderThanMaxStaleInputAge_refusesWithStaleInput() {
    RiskManager staleRm =
        new RiskManager(DEFAULT_PARAMS, Clock.fixed(T0.plus(Duration.ofHours(2)), ZoneOffset.UTC));
    var decision = staleRm.gate(buySignal(0.8), freshState(bd("10000")), ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Refuse.class);
    assertThat(((ExecutionDecision.Refuse) decision).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.STALE_INPUT);
  }

  @Test
  void signalWithinMaxStaleInputAge_isAllowed() {
    RiskManager freshRm =
        new RiskManager(
            DEFAULT_PARAMS, Clock.fixed(T0.plus(Duration.ofMinutes(30)), ZoneOffset.UTC));
    var decision = freshRm.gate(buySignal(0.8), freshState(bd("10000")), ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
  }

  // ── 6. Leverage cap ─────────────────────────────────────────────────────────────────────────

  @Test
  void openPositionAlreadyAtMaxLeverage_refusesAdditional() {
    // Equity = 1,000; existing open notional = 2,000 (2× leverage = max).
    // New position would take total notional above 2×.
    var stateWithPosition =
        new PortfolioState(
            bd("0"),
            bd("1000"),
            bd("1000"),
            Map.of("BTCUSDT", bd("2000")),
            false,
            Optional.empty());
    var decision = RM.gate(buySignal(0.8), stateWithPosition, ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Refuse.class);
    assertThat(((ExecutionDecision.Refuse) decision).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.LEVERAGE_CAP_EXCEEDED);
  }

  // ── 7. Daily volume cap ─────────────────────────────────────────────────────────────────────

  @Test
  void dailyVolumeCap_refusesWhenPositionIsTooBig() {
    // Very low volume candles: 0.001 BTC per bar → 24 bars = 0.024 BTC daily volume.
    // 0.5% of 0.024 BTC = 0.00012 BTC. With equity $10,000 and 0.005% risk, computed size
    // should be tiny, but let's use a large equity to force the position above the cap.
    List<Candle> thinCandles = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      thinCandles.add(
          new Candle(
              T0.plusSeconds(3600L * i),
              bd("50000"),
              bd("50100"),
              bd("49900"),
              bd("50000"),
              bd("0.001")));
    }
    // With $10,000,000 equity: size = 10,000,000 * 0.005 / ATR ≈ huge → capped at 2% of
    // equity / price = 200,000 * 2% / 50,000 = 4 BTC. But daily volume is 24 * 0.001 = 0.024.
    // 0.5% of 0.024 = 0.00012 BTC. 4 BTC >> 0.00012 → DAILY_VOLUME_CAP_EXCEEDED.
    var bigState =
        new PortfolioState(
            bd("10000000"), bd("10000000"), bd("10000000"), Map.of(), false, Optional.empty());
    var decision = RM.gate(buySignal(0.8), bigState, ctx(thinCandles));
    assertThat(decision).isInstanceOf(ExecutionDecision.Refuse.class);
    assertThat(((ExecutionDecision.Refuse) decision).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.DAILY_VOLUME_CAP_EXCEEDED);
  }

  // ── F11. Supplied ATR is used instead of recomputed ────────────────────────────────────────────

  @Test
  void suppliedAtr_isPreferredOverRecomputedAtr() {
    // flatCandles' true ATR is small (≈200 high-low range); supplying a deliberately different
    // value (500) and checking it flows straight through to stopLossDistance = atr × mult proves
    // the RM used the supplied value rather than recomputing its own from the candles.
    MarketContext ctxWithSuppliedAtr =
        new MarketContext(
            "BTCUSDT", CandleInterval.ONE_HOUR, flatCandles(50), List.of(), Optional.of(bd("500")));

    var decision = RM.gate(buySignal(0.8), freshState(bd("10000")), ctxWithSuppliedAtr);

    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
    var exec = (ExecutionDecision.Execute) decision;
    // stopLossDistance = suppliedAtr(500) × stopLossAtrMult(2.0) = 1000.
    assertThat(exec.stopLossDistance()).isEqualByComparingTo(bd("1000"));
  }

  // ── 8. Determinism ──────────────────────────────────────────────────────────────────────────

  @Test
  void identicalInputs_produceIdenticalOutputs() {
    var candles = flatCandles(50);
    var state = freshState(bd("10000"));
    var signal = buySignal(0.8);

    var d1 = RM.gate(signal, state, ctx(candles));
    var d2 = RM.gate(signal, state, ctx(candles));

    assertThat(d1).isInstanceOf(ExecutionDecision.Execute.class);
    assertThat(d2).isInstanceOf(ExecutionDecision.Execute.class);
    assertThat(((ExecutionDecision.Execute) d1).size())
        .isEqualByComparingTo(((ExecutionDecision.Execute) d2).size());
    assertThat(((ExecutionDecision.Execute) d1).stopLoss())
        .isEqualByComparingTo(((ExecutionDecision.Execute) d2).stopLoss());
  }

  // ── PLAN-010 Task D1. Exchange trading filters ──────────────────────────────────────────────

  private static MarketContext ctxWithFilters(
      List<Candle> candles, BigDecimal suppliedAtr, ExchangeFilters filters) {
    return new MarketContext(
        "BTCUSDT",
        CandleInterval.ONE_HOUR,
        candles,
        List.of(),
        Optional.of(suppliedAtr),
        Optional.of(filters));
  }

  @Test
  void noExchangeFilters_behaviorIdenticalToBefore() {
    // ctx() (no filters) is what every other test in this class already exercises -- this just
    // pins the "absent means no-op" contract explicitly.
    var decision = RM.gate(buySignal(0.8), freshState(bd("10000")), ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
  }

  @Test
  void buySignal_sizeRoundsDownToQtyStepSize_neverUp() {
    // Uncapped RM sizing here is 0.004 BTC (see
    // happyPath_producesExecuteWithPositiveSizeAndStopLoss)
    // -- a step size that doesn't evenly divide it (0.003) makes the rounding visible.
    ExchangeFilters filters = new ExchangeFilters(bd("1"), bd("0.003"), bd("0.10"));
    var decision =
        RM.gate(
            buySignal(0.8),
            freshState(bd("10000")),
            ctxWithFilters(flatCandles(50), bd("200"), filters));
    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
    var exec = (ExecutionDecision.Execute) decision;
    assertThat(exec.size()).isEqualByComparingTo(bd("0.003"));
  }

  @Test
  void buySignal_notionalBelowExchangeMinimum_refuses() {
    ExchangeFilters filters = new ExchangeFilters(bd("1000000"), bd("0.0001"), bd("0.10"));
    var decision =
        RM.gate(
            buySignal(0.8),
            freshState(bd("10000")),
            ctxWithFilters(flatCandles(50), bd("200"), filters));
    assertThat(decision).isInstanceOf(ExecutionDecision.Refuse.class);
    assertThat(((ExecutionDecision.Refuse) decision).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.BELOW_EXCHANGE_MINIMUM);
  }

  @Test
  void buySignal_qtyRoundsToZero_refusesWithBelowExchangeMinimum() {
    // Step size far larger than the ~0.004 BTC uncapped size -> rounds to zero.
    ExchangeFilters filters = new ExchangeFilters(bd("1"), bd("1"), bd("0.10"));
    var decision =
        RM.gate(
            buySignal(0.8),
            freshState(bd("10000")),
            ctxWithFilters(flatCandles(50), bd("200"), filters));
    assertThat(decision).isInstanceOf(ExecutionDecision.Refuse.class);
    assertThat(((ExecutionDecision.Refuse) decision).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.BELOW_EXCHANGE_MINIMUM);
  }

  @Test
  void buySignal_stopLossRoundsUpTowardEntry_tighterThanUnrounded() {
    // entryPrice=50000 (flatCandles' last close); suppliedAtr=333.33, stopLossAtrMult=2.0 (default)
    // -> raw stopLossDistance=666.66, raw stopLossPrice=50000-666.66=49333.34 (not a 0.10 tick).
    ExchangeFilters filters = new ExchangeFilters(bd("1"), bd("0.0001"), bd("0.10"));
    var decision =
        RM.gate(
            buySignal(0.8),
            freshState(bd("10000")),
            ctxWithFilters(flatCandles(50), bd("333.33"), filters));
    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
    var exec = (ExecutionDecision.Execute) decision;
    // Rounded UP (toward the 50000 entry) from the raw 49333.34 -- tighter, triggers sooner.
    assertThat(exec.stopLoss()).isEqualByComparingTo(bd("49333.40"));
    assertThat(exec.stopLoss()).isGreaterThan(bd("49333.34"));
    // stopLossDistance stays consistent with the rounded absolute price.
    assertThat(exec.stopLossDistance()).isEqualByComparingTo(bd("666.60"));
  }

  @Test
  void buySignal_takeProfitRoundsDownTowardEntry_lessThanUnrounded() {
    // Same setup as the stop-loss test: raw tpDistance=1333.32, raw tpPrice=51333.32.
    ExchangeFilters filters = new ExchangeFilters(bd("1"), bd("0.0001"), bd("0.10"));
    var decision =
        RM.gate(
            buySignal(0.8),
            freshState(bd("10000")),
            ctxWithFilters(flatCandles(50), bd("333.33"), filters));
    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
    var exec = (ExecutionDecision.Execute) decision;
    // Rounded DOWN (toward the 50000 entry) from the raw 51333.32 -- less profit booked.
    assertThat(exec.takeProfit()).isPresent();
    BigDecimal tpPrice = bd("50000").add(exec.takeProfit().get());
    assertThat(tpPrice).isEqualByComparingTo(bd("51333.30"));
    assertThat(tpPrice).isLessThan(bd("51333.32"));
  }

  @Test
  void sellSignal_exchangeFiltersNeverRefuseAnExit() {
    // A minNotional no realistic position could clear -- if this were entry-gated like BUY, every
    // close would be refused. D2's "never block exits" contract: SELL is exempt entirely.
    ExchangeFilters filters = new ExchangeFilters(bd("999999999"), bd("1"), bd("0.10"));
    var stateWithPosition =
        new PortfolioState(
            bd("5000"),
            bd("10000"),
            bd("10000"),
            Map.of("BTCUSDT", bd("5000")),
            false,
            Optional.empty());
    var decision =
        RM.gate(
            sellSignal(0.8),
            stateWithPosition,
            ctxWithFilters(flatCandles(50), bd("200"), filters));
    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
  }

  /**
   * PLAN-010 Task D4's property test: across random account equity and random (but individually
   * valid) exchange filters, an {@code Execute} decision's size is always an exact multiple of
   * {@code qtyStepSize}, always clears {@code minNotional}, and never exceeds §7's 2% position-size
   * cap regardless of the filters — or the signal is refused. Never both violated and executed.
   */
  @Property
  void randomEquityAndFilters_executedSizeNeverViolatesCapsOrFilters_orIsRefused(
      @ForAll @DoubleRange(min = 100.0, max = 10_000_000.0) double equityD,
      @ForAll @DoubleRange(min = 0.0001, max = 1000.0) @Scale(5) double minNotionalD,
      @ForAll @DoubleRange(min = 0.00001, max = 10.0) @Scale(5) double qtyStepD,
      @ForAll @DoubleRange(min = 0.001, max = 100.0) @Scale(5) double priceTickD) {
    BigDecimal equity = BigDecimal.valueOf(equityD);
    ExchangeFilters filters =
        new ExchangeFilters(
            BigDecimal.valueOf(minNotionalD),
            BigDecimal.valueOf(qtyStepD),
            BigDecimal.valueOf(priceTickD));

    var decision =
        RM.gate(
            buySignal(0.8),
            freshState(equity),
            ctxWithFilters(flatCandles(50), bd("200"), filters));

    if (decision instanceof ExecutionDecision.Execute exec) {
      BigDecimal entryPrice = bd("50000"); // flatCandles' constant close
      BigDecimal maxSizeByPct =
          equity
              .multiply(DEFAULT_PARAMS.maxPositionPct(), IndicatorMath.MC)
              .divide(entryPrice, IndicatorMath.MC);
      assertThat(exec.size()).isLessThanOrEqualTo(maxSizeByPct);

      BigDecimal steps = exec.size().divideToIntegralValue(filters.qtyStepSize(), IndicatorMath.MC);
      assertThat(steps.multiply(filters.qtyStepSize(), IndicatorMath.MC))
          .isEqualByComparingTo(exec.size());

      BigDecimal notional = exec.size().multiply(entryPrice, IndicatorMath.MC);
      assertThat(notional).isGreaterThanOrEqualTo(filters.minNotional());
    }
    // else: refused -- always a valid outcome too (D10-2: refuse, never bump past the caps).
  }

  // ── PLAN-012 Task F: absolute-dollar limits ─────────────────────────────────────────────────
  //
  // With equity=10000 and flatCandles(50), the ATR-derived raw size is always far larger than the
  // 2%-of-equity cap, so that percentage cap is the binding constraint (confirmed by
  // happyPath_producesExecuteWithPositiveSizeAndStopLoss's own assertion above) -- meaning
  // positionNotional is *exactly* equity x maxPositionPct = 10000 x 0.02 = $200.00, a clean
  // BigDecimal value with no rounding, which is what makes an exact boundary-case test possible
  // below rather than an approximate one.

  private static RiskManager rmWithAbsoluteLimits(
      Optional<BigDecimal> maxTotalDeployedAbs,
      Optional<BigDecimal> maxPositionAbs,
      Optional<BigDecimal> maxDailyLossAbs,
      Optional<BigDecimal> maxCampaignLossAbs) {
    RiskParameters params =
        RiskParameters.defaultsWithAbsoluteLimits(
            maxTotalDeployedAbs, maxPositionAbs, maxDailyLossAbs, maxCampaignLossAbs);
    return new RiskManager(params, Clock.fixed(T0, ZoneOffset.UTC));
  }

  @Test
  void maxPositionAbs_belowComputedNotional_refuses() {
    RiskManager rm =
        rmWithAbsoluteLimits(
            Optional.empty(), Optional.of(bd("100")), Optional.empty(), Optional.empty());
    var decision = rm.gate(buySignal(0.8), freshState(bd("10000")), ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Refuse.class);
    assertThat(((ExecutionDecision.Refuse) decision).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.ABSOLUTE_EXPOSURE_CAP_EXCEEDED);
  }

  @Test
  void maxPositionAbs_exactlyAtComputedNotional_isAllowed() {
    // Boundary: computed notional is exactly $200 (see class comment above); the check is '>',
    // not '>=', so a limit exactly equal to the notional must NOT refuse.
    RiskManager rm =
        rmWithAbsoluteLimits(
            Optional.empty(), Optional.of(bd("200")), Optional.empty(), Optional.empty());
    var decision = rm.gate(buySignal(0.8), freshState(bd("10000")), ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
  }

  @Test
  void maxPositionAbs_oneCentAboveComputedNotional_isAllowed() {
    RiskManager rm =
        rmWithAbsoluteLimits(
            Optional.empty(), Optional.of(bd("200.01")), Optional.empty(), Optional.empty());
    var decision = rm.gate(buySignal(0.8), freshState(bd("10000")), ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
  }

  @Test
  void maxPositionAbs_absent_neverRefuses_evenWithATinyComputedNotionalLimitWouldHaveHit() {
    // Sanity: DEFAULT_PARAMS (no absolute limits configured) must behave exactly as before this
    // task -- this is the "byte-identical backtest output" acceptance bar in miniature.
    var decision = RM.gate(buySignal(0.8), freshState(bd("10000")), ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
  }

  @Test
  void maxTotalDeployedAbs_belowComputedAggregateNotional_refuses() {
    // Existing $1800 position + this $200 trade = $2000 aggregate; cap it at $1000.
    RiskManager rm =
        rmWithAbsoluteLimits(
            Optional.of(bd("1000")), Optional.empty(), Optional.empty(), Optional.empty());
    PortfolioState state =
        new PortfolioState(
            bd("8200"),
            bd("10000"),
            bd("10000"),
            Map.of("ETHUSDT", bd("1800")),
            false,
            Optional.empty());
    var decision = rm.gate(buySignal(0.8), state, ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Refuse.class);
    assertThat(((ExecutionDecision.Refuse) decision).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.ABSOLUTE_EXPOSURE_CAP_EXCEEDED);
  }

  @Test
  void maxTotalDeployedAbs_exactlyAtAggregateNotional_isAllowed() {
    // Boundary: $1800 existing + $200 new = exactly $2000.
    RiskManager rm =
        rmWithAbsoluteLimits(
            Optional.of(bd("2000")), Optional.empty(), Optional.empty(), Optional.empty());
    PortfolioState state =
        new PortfolioState(
            bd("8200"),
            bd("10000"),
            bd("10000"),
            Map.of("ETHUSDT", bd("1800")),
            false,
            Optional.empty());
    var decision = rm.gate(buySignal(0.8), state, ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
  }

  @Test
  void maxDailyLossAbs_lossAtOrAboveLimit_refuses() {
    RiskManager rm =
        rmWithAbsoluteLimits(
            Optional.empty(), Optional.empty(), Optional.of(bd("50")), Optional.empty());
    // Day-start equity $10,000, current equity $9,940 -- $60 daily loss >= $50 limit.
    var decision =
        rm.gate(
            buySignal(0.8), freshStateWithDayStart(bd("9940"), bd("10000")), ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Refuse.class);
    assertThat(((ExecutionDecision.Refuse) decision).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.DAILY_LOSS_LIMIT_REACHED);
  }

  @Test
  void maxDailyLossAbs_exactlyAtLimit_refuses() {
    // Boundary: '>=' semantics (matching CircuitBreaker's own convention), unlike the '>' notional
    // checks above -- loss of exactly $50 against a $50 limit must refuse.
    RiskManager rm =
        rmWithAbsoluteLimits(
            Optional.empty(), Optional.empty(), Optional.of(bd("50")), Optional.empty());
    var decision =
        rm.gate(
            buySignal(0.8), freshStateWithDayStart(bd("9950"), bd("10000")), ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Refuse.class);
    assertThat(((ExecutionDecision.Refuse) decision).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.DAILY_LOSS_LIMIT_REACHED);
  }

  @Test
  void maxDailyLossAbs_oneCentBelowLimit_isAllowed() {
    RiskManager rm =
        rmWithAbsoluteLimits(
            Optional.empty(), Optional.empty(), Optional.of(bd("50")), Optional.empty());
    var decision =
        rm.gate(
            buySignal(0.8),
            freshStateWithDayStart(bd("9950.01"), bd("10000")),
            ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
  }

  @Test
  void maxDailyLossAbs_configuredButNoDayStartEquityTracked_neverFires() {
    // The opt-in contract: absent dayStartEquity (every backtest harness) is a no-op even when the
    // limit itself is configured -- never a fail-safe refusal, since this is an extra live-phase
    // guard, not a required risk input (unlike, say, missing ATR/volume).
    RiskManager rm =
        rmWithAbsoluteLimits(
            Optional.empty(), Optional.empty(), Optional.of(bd("1")), Optional.empty());
    var decision = rm.gate(buySignal(0.8), freshState(bd("9000")), ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
  }

  @Test
  void maxDailyLossAbs_dayStartEquityTrackedButLimitNotConfigured_neverFires() {
    // The other half of the opt-in contract: a huge apparent daily loss is irrelevant when the RM
    // itself has no configured limit to check it against.
    var decision =
        RM.gate(
            buySignal(0.8), freshStateWithDayStart(bd("100"), bd("10000")), ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Execute.class);
  }

  @Test
  void dailyLossCheck_runsBeforeConfidenceFloor_soADoomedSignalIsRefusedForTheRightReason() {
    // Gate-order regression: even a low-confidence signal must be refused for the daily-loss
    // reason first if that's the more fundamental problem, matching the circuit-breaker check's
    // own precedence (both are "should we be trading at all" checks, ahead of signal-quality
    // checks).
    RiskManager rm =
        rmWithAbsoluteLimits(
            Optional.empty(), Optional.empty(), Optional.of(bd("50")), Optional.empty());
    var decision =
        rm.gate(
            buySignal(0.1), freshStateWithDayStart(bd("9940"), bd("10000")), ctx(flatCandles(50)));
    assertThat(decision).isInstanceOf(ExecutionDecision.Refuse.class);
    assertThat(((ExecutionDecision.Refuse) decision).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.DAILY_LOSS_LIMIT_REACHED);
  }
}
