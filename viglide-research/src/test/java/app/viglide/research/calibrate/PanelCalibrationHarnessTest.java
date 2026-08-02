package app.viglide.research.calibrate;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.EconomicMetrics;
import app.viglide.core.backtest.FeeModel;
import app.viglide.core.calibrate.Candidate;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.spi.StrategyMetadata;
import app.viglide.core.spi.TradingStrategy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PanelCalibrationHarness} (PLAN-013 Task G, review finding F4). */
class PanelCalibrationHarnessTest {

  private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
  // RiskManager.ATR_PERIOD=14 needs >=15 candles in the rolling window before it will size a
  // trade at all (falls back to computing ATR internally when MarketContext carries none, which
  // PortfolioBacktestHarness never does) -- BacktestConfig.warmupBars() must clear that bar.
  private static final int WARMUP = 20;

  @Test
  void run_producesOneRankedResultPerCandidate_notPerPair() {
    Map<String, List<Candle>> candles =
        Map.of(
            "LONGPAIR", ascendingTrend(WARMUP + 100, 0.0005),
            "SHORTPAIR", ascendingTrend(WARMUP + 100, 0.0005));
    Candidate candidateA = new Candidate(new BuyOnceStrategy(), Map.of("variant", "A"));
    Candidate candidateB = new Candidate(new BuyOnceStrategy(), Map.of("variant", "B"));

    List<PanelCalibrationHarness.PanelResult> results =
        PanelCalibrationHarness.run(
            candles,
            Map.of(),
            Map.of(),
            CandleInterval.ONE_HOUR,
            testConfig(),
            List.of(candidateA, candidateB).stream(),
            // Fresh instance per symbol -- BuyOnceStrategy is deliberately stateful (test-only;
            // real strategies are stateless per NFR-7), so sharing base.strategy() across symbols
            // would let one pair's "already bought" state leak into another's evaluation.
            (base, scale) -> new BuyOnceStrategy(),
            1,
            null,
            null,
            100);

    // One PanelResult per CANDIDATE (2), each already pooled across both pairs -- not one row per
    // (candidate, pair) combination (which would be 4).
    assertThat(results).hasSize(2);
    assertThat(results).allSatisfy(r -> assertThat(r.totalPairs()).isEqualTo(2));
  }

  @Test
  void run_isDeterministic_sameInputsTwiceGiveIdenticalRanking() {
    Map<String, List<Candle>> candles =
        Map.of(
            "PAIRA", ascendingTrend(WARMUP + 50, 0.0003),
            "PAIRB", ascendingTrend(WARMUP + 50, 0.0007));
    List<Candidate> candidates =
        List.of(
            new Candidate(new BuyOnceStrategy(), Map.of("variant", "A")),
            new Candidate(new BuyOnceStrategy(), Map.of("variant", "B")));

    List<PanelCalibrationHarness.PanelResult> run1 =
        PanelCalibrationHarness.run(
            candles,
            Map.of(),
            Map.of(),
            CandleInterval.ONE_HOUR,
            testConfig(),
            candidates.stream(),
            (base, scale) -> new BuyOnceStrategy(),
            2,
            null,
            null,
            100);
    List<PanelCalibrationHarness.PanelResult> run2 =
        PanelCalibrationHarness.run(
            candles,
            Map.of(),
            Map.of(),
            CandleInterval.ONE_HOUR,
            testConfig(),
            candidates.stream(),
            (base, scale) -> new BuyOnceStrategy(),
            2,
            null,
            null,
            100);

    assertThat(run1).isEqualTo(run2);
  }

  @Test
  void crossPairSignConsistency_countsOnlyPairsThatTraded_notTheWholeUniverse() {
    // PAIRTRADES trades and wins; PAIRIDLE never signals at all (flat price, AlwaysHoldStrategy) --
    // it must not count against consistency just for existing in the universe.
    Map<String, List<Candle>> candles =
        Map.of(
            "PAIRTRADES", ascendingTrend(WARMUP + 30, 0.001),
            "PAIRIDLE", flat(WARMUP + 30));
    TradingStrategy strategy =
        symbolAware(
            Map.of(
                "PAIRTRADES", new BuyOnceStrategy(),
                "PAIRIDLE", new NeverTradeStrategy()));
    Candidate candidate = new Candidate(strategy, Map.of("test", "x"));

    List<PanelCalibrationHarness.PanelResult> results =
        PanelCalibrationHarness.run(
            candles,
            Map.of(),
            Map.of(),
            CandleInterval.ONE_HOUR,
            testConfig(),
            List.of(candidate).stream(),
            (base, scale) -> base.strategy(),
            1,
            null,
            null,
            100);

    PanelCalibrationHarness.PanelResult result = results.get(0);
    assertThat(result.totalPairs()).isEqualTo(2);
    assertThat(result.pairsWithTrades()).isEqualTo(1); // only PAIRTRADES
    assertThat(result.crossPairSignConsistency()).isEqualTo(1.0); // 1 of 1 traded pairs won
  }

  @Test
  void pooledReturnOnDeployedCapital_weightsByCapitalDays_notByPairCount() {
    // LONGPAIR: held ~100 hours, a smaller per-hour appreciation. SHORTPAIR: held ~10 hours, a
    // LARGER per-hour appreciation -- SHORTPAIR's own annualised rate is far higher, but it
    // contributes far fewer capital-days. A capital-days-weighted pool must sit much closer to
    // LONGPAIR's own rate than a naive 50/50 average of the two pairs' own rates would.
    List<Candle> longSeries = ascendingTrend(WARMUP + 100, 0.0004); // ~100h hold after warmup
    List<Candle> shortSeries = ascendingTrend(WARMUP + 10, 0.004); // ~10h hold, 10x steeper
    Map<String, List<Candle>> candles = Map.of("LONGPAIR", longSeries, "SHORTPAIR", shortSeries);
    Candidate candidate = new Candidate(new BuyOnceStrategy(), Map.of("test", "x"));

    List<PanelCalibrationHarness.PanelResult> pooled =
        PanelCalibrationHarness.run(
            candles,
            Map.of(),
            Map.of(),
            CandleInterval.ONE_HOUR,
            testConfig(),
            List.of(candidate).stream(),
            (base, scale) -> new BuyOnceStrategy(),
            1,
            null,
            null,
            100);
    BigDecimal pooledRate = pooled.get(0).pooledReturnOnDeployedCapital();

    // Each pair's OWN rate, computed independently (same mechanism, single-pair) -- the naive,
    // WRONG "average the pairs" alternative this harness must NOT reduce to.
    BigDecimal longOwnRate = ownPairRate(longSeries, "LONGPAIR");
    BigDecimal shortOwnRate = ownPairRate(shortSeries, "SHORTPAIR");
    BigDecimal naiveAverage =
        longOwnRate
            .add(shortOwnRate)
            .divide(BigDecimal.valueOf(2), java.math.MathContext.DECIMAL64);

    assertThat(shortOwnRate)
        .isGreaterThan(longOwnRate); // confirms the fixture: short pair's OWN rate is far richer
    // The properly-weighted pool sits closer to the capital-days-dominant (long) pair's own rate
    // than the naive average does -- i.e. strictly below the naive average, since the naive
    // average is inflated by the thin, short-lived pair's outsized rate.
    assertThat(pooledRate).isLessThan(naiveAverage);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────────

  /**
   * {@code warmupBars=WARMUP}, not {@code BacktestConfig.hourlyDefaults()}'s 200 -- these fixture
   * series are far shorter than 200 candles, and a window that never fills never evaluates at all.
   */
  private static BacktestConfig testConfig() {
    return new BacktestConfig(
        BigDecimal.valueOf(10_000),
        FeeModel.binanceDefault(),
        WARMUP,
        BigDecimal.valueOf(0.02),
        BigDecimal.valueOf(0.02),
        BigDecimal.valueOf(0.04),
        8760);
  }

  /** One pair's own returnOnDeployedCapital, computed independently via the single-pair harness. */
  private static BigDecimal ownPairRate(List<Candle> series, String symbol) {
    var result =
        app.viglide.core.backtest.BacktestHarness.run(
            new BuyOnceStrategy(), series.stream(), symbol, CandleInterval.ONE_HOUR, testConfig());
    return EconomicMetrics.returnOnDeployedCapital(result);
  }

  /** Strictly ascending close price; {@code perHourRate} controls how steep. */
  private static List<Candle> ascendingTrend(int n, double perHourRate) {
    List<Candle> out = new ArrayList<>(n);
    BigDecimal price = BigDecimal.valueOf(100);
    BigDecimal rate = BigDecimal.valueOf(1 + perHourRate);
    for (int i = 0; i < n; i++) {
      out.add(candle(i, price));
      price = price.multiply(rate, java.math.MathContext.DECIMAL64);
    }
    return out;
  }

  private static List<Candle> flat(int n) {
    List<Candle> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      out.add(candle(i, BigDecimal.valueOf(100)));
    }
    return out;
  }

  private static Candle candle(int i, BigDecimal close) {
    return new Candle(
        T0.plusSeconds(3600L * i),
        close,
        close.add(BigDecimal.valueOf(0.1)),
        close.subtract(BigDecimal.valueOf(0.1)),
        close,
        BigDecimal.valueOf(1000));
  }

  /**
   * Dispatches to a per-symbol strategy based on {@code ctx.symbol()} -- lets one Candidate drive
   * different fixed behaviours per pair within a single panel run, for tests only.
   */
  private static TradingStrategy symbolAware(Map<String, TradingStrategy> bySymbol) {
    return new TradingStrategy() {
      @Override
      public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
        return bySymbol.get(ctx.symbol()).evaluate(ctx);
      }

      @Override
      public StrategyMetadata metadata() {
        return new StrategyMetadata("SymbolAware", "0.0.1", "PLAN-013 Task G test-only");
      }
    };
  }

  /** BUY on the first evaluation only, HOLD forever after -- exactly one trade, open-to-EOD. */
  private static final class BuyOnceStrategy implements TradingStrategy {
    private boolean bought;

    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
      Direction d = bought ? Direction.HOLD : Direction.BUY;
      bought = true;
      return Optional.of(
          new TechnicalSignal(ctx.symbol(), d, 0.9, List.of(), "buy once", ctx.asOf()));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("BuyOnce", "0.0.1", "PLAN-013 Task G test-only");
    }
  }

  private static final class NeverTradeStrategy implements TradingStrategy {
    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
      return Optional.of(
          new TechnicalSignal(ctx.symbol(), Direction.HOLD, 0.0, List.of(), "never", ctx.asOf()));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("NeverTrade", "0.0.1", "PLAN-013 Task G test-only");
    }
  }
}
