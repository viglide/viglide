package app.viglide.research.calibrate;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.FeeModel;
import app.viglide.core.calibrate.Candidate;
import app.viglide.core.calibrate.DoubleRange;
import app.viglide.core.calibrate.IntRange;
import app.viglide.core.data.CsvKlineReader;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.spi.StrategyMetadata;
import app.viglide.core.spi.TradingStrategy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for {@link CalibrationHarness}. Uses the committed BTC 1h month snippet to keep
 * runtime under a few seconds.
 */
class CalibrationHarnessTest {

  @Test
  void smallSearch_producesNonEmptyDeterministicRanking() throws Exception {
    Path dataset = Path.of("src/test/resources/fixtures/large_snippets/BTCUSDT_1h_month.csv");
    List<Candle> candles;
    try (var stream = CsvKlineReader.stream(dataset.toAbsolutePath())) {
      candles = stream.toList();
    }
    assertThat(candles).hasSizeGreaterThan(700);

    // Tiny search space: 1 × 2 × 1 × 1 × 1 × 1 = 2 combos.
    EmaRsiParameterSpace space =
        new EmaRsiParameterSpace(
            new IntRange(9, 9, 1),
            new IntRange(21, 23, 2),
            new IntRange(14, 14, 1),
            new DoubleRange(70.0, 70.0, 1.0),
            new DoubleRange(30.0, 30.0, 1.0),
            new DoubleRange(0.01, 0.01, 0.01));

    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.binanceDefault(),
            100,
            new BigDecimal("0.02"),
            new BigDecimal("0.02"),
            new BigDecimal("0.04"),
            8760);

    CalibrationRun run1 =
        CalibrationHarness.run(
            candles,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            space.grid(),
            cfg,
            3,
            2,
            0,
            null,
            null,
            100);
    CalibrationRun run2 =
        CalibrationHarness.run(
            candles,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            space.grid(),
            cfg,
            3,
            2,
            0,
            null,
            null,
            100);

    List<CalibrationResult> r1 = run1.survivors();
    List<CalibrationResult> r2 = run2.survivors();
    assertThat(r1).isNotEmpty();
    assertThat(r1).hasSameSizeAs(r2);
    assertThat(r1.get(0).cvSharpeMedian()).isEqualTo(r2.get(0).cvSharpeMedian());
    assertThat(r1.get(0).params()).isEqualTo(r2.get(0).params());
    // Trial accounting (PLAN-008 D.1) is deterministic too: same candidate count both times.
    assertThat(run1.trials()).isEqualTo(run2.trials()).isEqualTo((int) space.gridSize());
    assertThat(run1.trialSharpeVariancePerPeriod()).isEqualTo(run2.trialSharpeVariancePerPeriod());
  }

  @Test
  void minTrades_filtersOutLowVolumeCandidates() throws Exception {
    Path dataset = Path.of("src/test/resources/fixtures/large_snippets/BTCUSDT_1h_month.csv");
    List<Candle> candles;
    try (var stream = CsvKlineReader.stream(dataset.toAbsolutePath())) {
      candles = stream.toList();
    }

    EmaRsiParameterSpace space =
        new EmaRsiParameterSpace(
            new IntRange(9, 9, 1),
            new IntRange(21, 21, 1),
            new IntRange(14, 14, 1),
            new DoubleRange(70.0, 70.0, 1.0),
            new DoubleRange(30.0, 30.0, 1.0),
            new DoubleRange(0.01, 0.01, 0.01));

    BacktestConfig cfg = BacktestConfig.hourlyDefaults();
    CalibrationRun run =
        CalibrationHarness.run(
            candles,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            space.grid(),
            cfg,
            3,
            1,
            10_000,
            null,
            null,
            100);
    assertThat(run.survivors()).isEmpty();
    // Trials still counts everything evaluated, even though minTrades filtered all of it out.
    assertThat(run.trials()).isEqualTo((int) space.gridSize());
  }

  // ── PLAN-013 Task D: injectable ScoringFunction, total-vs-median trade filter ───────────────

  @Test
  void legacyOverload_reproducesTheExplicitMedianCvSharpeOverload_byteIdentically()
      throws Exception {
    // Task D acceptance criterion: MEDIAN_CV_SHARPE reproduces a previously recorded run
    // byte-identically. The pre-PLAN-013 (fewer-parameter) overload must produce output
    // indistinguishable from explicitly requesting MEDIAN_CV_SHARPE + the legacy median filter on
    // the full overload -- proving the "old callers see zero behaviour change" backward-
    // compatibility claim, not just asserting it in a comment.
    Path dataset = Path.of("src/test/resources/fixtures/large_snippets/BTCUSDT_1h_month.csv");
    List<Candle> candles;
    try (var stream = CsvKlineReader.stream(dataset.toAbsolutePath())) {
      candles = stream.toList();
    }
    EmaRsiParameterSpace space =
        new EmaRsiParameterSpace(
            new IntRange(9, 9, 1),
            new IntRange(21, 23, 2),
            new IntRange(14, 14, 1),
            new DoubleRange(70.0, 70.0, 1.0),
            new DoubleRange(30.0, 30.0, 1.0),
            new DoubleRange(0.01, 0.01, 0.01));
    BacktestConfig cfg = BacktestConfig.hourlyDefaults();

    CalibrationRun legacy =
        CalibrationHarness.run(
            candles,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            space.grid(),
            cfg,
            3,
            2,
            0,
            null,
            null,
            100);
    CalibrationRun explicit =
        CalibrationHarness.run(
            candles,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            space.grid(),
            cfg,
            3,
            2,
            0,
            null,
            null,
            100,
            FoldRunner.defaultOhlcv(),
            ScoringFunction.MEDIAN_CV_SHARPE,
            true);

    assertThat(explicit.survivors()).isEqualTo(legacy.survivors());
    assertThat(explicit.trials()).isEqualTo(legacy.trials());
    assertThat(explicit.trialSharpeVariancePerPeriod())
        .isEqualTo(legacy.trialSharpeVariancePerPeriod());
  }

  @Test
  void carryYieldObjective_isDeterministic_sameInputsTwiceGiveIdenticalRanking() throws Exception {
    Path dataset = Path.of("src/test/resources/fixtures/large_snippets/BTCUSDT_1h_month.csv");
    List<Candle> candles;
    try (var stream = CsvKlineReader.stream(dataset.toAbsolutePath())) {
      candles = stream.toList();
    }
    EmaRsiParameterSpace space =
        new EmaRsiParameterSpace(
            new IntRange(9, 9, 1),
            new IntRange(21, 25, 2),
            new IntRange(14, 14, 1),
            new DoubleRange(70.0, 70.0, 1.0),
            new DoubleRange(30.0, 30.0, 1.0),
            new DoubleRange(0.01, 0.01, 0.01));
    BacktestConfig cfg = BacktestConfig.hourlyDefaults();

    CalibrationRun run1 =
        CalibrationHarness.run(
            candles,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            space.grid(),
            cfg,
            3,
            2,
            0,
            null,
            null,
            100,
            FoldRunner.defaultOhlcv(),
            ScoringFunction.CARRY_YIELD,
            false);
    CalibrationRun run2 =
        CalibrationHarness.run(
            candles,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            space.grid(),
            cfg,
            3,
            2,
            0,
            null,
            null,
            100,
            FoldRunner.defaultOhlcv(),
            ScoringFunction.CARRY_YIELD,
            false);

    assertThat(run1.survivors()).isEqualTo(run2.survivors());
  }

  @Test
  void tieBreak_isStableParamsOrder_notInsertionOrHashOrder() {
    // Two candidates engineered to score identically under a constant ScoringFunction -- the sort
    // must still produce the SAME order every time, driven by the stable params-map serialisation,
    // not by whatever order the parallel stream happened to emit them in.
    List<Candle> candles = ascendingTrend(60); // 3 folds x 20 bars, warmupBars=10
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 10, BigDecimal.ONE, null, null, 8760);
    Candidate candidateA = new Candidate(new AlwaysBuyStrategy(), java.util.Map.of("variant", "A"));
    Candidate candidateB = new Candidate(new AlwaysBuyStrategy(), java.util.Map.of("variant", "B"));
    ScoringFunction constantScore = r -> 1.0; // every candidate ties

    CalibrationRun run1 =
        CalibrationHarness.run(
            candles,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            List.of(candidateA, candidateB).stream(),
            cfg,
            3,
            1,
            0,
            null,
            null,
            100,
            FoldRunner.defaultOhlcv(),
            constantScore,
            false);
    CalibrationRun run2 =
        CalibrationHarness.run(
            candles,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            List.of(candidateA, candidateB).stream(),
            cfg,
            3,
            1,
            0,
            null,
            null,
            100,
            FoldRunner.defaultOhlcv(),
            constantScore,
            false);

    assertThat(run1.survivors()).hasSize(2);
    assertThat(run1.survivors()).isEqualTo(run2.survivors());
    // "A" < "B" lexicographically -- stableParamsKey sorts by the serialised params string.
    assertThat(run1.survivors().get(0).params()).isEqualTo(java.util.Map.of("variant", "A"));
  }

  @Test
  void minTrades_totalVsMedianFilter_disagreeOnAnUnevenlyDistributedCandidate() throws Exception {
    // PLAN-013 Task D item 3: a candidate trading real (non-END_OF_DATA) round trips in only ONE
    // of three folds has cvTradeCountMedian=0 (the median of {0,0,N} is 0 for N folds >= 2 zeros)
    // -- filtered out by the legacy median filter at any minTrades > 0 -- but cvTradeCountTotal=N
    // (survives the new total filter at minTrades<=N). This is the exact near-inert-filter problem
    // the plan text describes: a 1-2-trades-per-fold strategy's median is zero (or near it) almost
    // by construction, regardless of how real its total trading activity is.
    List<Candle> candles = ascendingTrend(60); // 3 folds x 20 bars, warmupBars=10
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 10, BigDecimal.ONE, null, null, 8760);
    // Fold boundaries with warmupBars=10 over 60 candles / 3 folds: each fold's own chunk is bars
    // [10,30), [30,50), [50,70) roughly -- burst trading only from bar 50 onward concentrates every
    // real trade in the LAST fold, leaving the first two folds at zero.
    Candidate candidate =
        new Candidate(new BurstFromBarStrategy(50), java.util.Map.of("test", "burst"));

    CalibrationRun legacyFilter =
        CalibrationHarness.run(
            candles,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            List.of(candidate).stream(),
            cfg,
            3,
            1,
            1, // minTrades=1
            null,
            null,
            100,
            FoldRunner.defaultOhlcv(),
            ScoringFunction.MEDIAN_CV_SHARPE,
            true); // legacy median filter
    CalibrationRun totalFilter =
        CalibrationHarness.run(
            candles,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            List.of(candidate).stream(),
            cfg,
            3,
            1,
            1, // minTrades=1
            null,
            null,
            100,
            FoldRunner.defaultOhlcv(),
            ScoringFunction.MEDIAN_CV_SHARPE,
            false); // new total filter

    assertThat(legacyFilter.survivors()).isEmpty(); // median trade count is 0 -> filtered out
    assertThat(totalFilter.survivors()).hasSize(1); // total trade count > 0 -> survives
    assertThat(totalFilter.survivors().get(0).cvTradeCountMedian()).isZero();
    assertThat(totalFilter.survivors().get(0).cvTradeCountTotal()).isPositive();
  }

  // ── PLAN-013 Task F: runPurged (finding F7) ─────────────────────────────────────────────────

  @Test
  void runPurged_producesAValidRankedRunAndANonZeroPurgeReport() throws Exception {
    Path dataset = Path.of("src/test/resources/fixtures/large_snippets/BTCUSDT_1h_month.csv");
    List<Candle> candles;
    try (var stream = CsvKlineReader.stream(dataset.toAbsolutePath())) {
      candles = stream.toList();
    }
    EmaRsiParameterSpace space =
        new EmaRsiParameterSpace(
            new IntRange(9, 9, 1),
            new IntRange(21, 23, 2),
            new IntRange(14, 14, 1),
            new DoubleRange(70.0, 70.0, 1.0),
            new DoubleRange(30.0, 30.0, 1.0),
            new DoubleRange(0.01, 0.01, 0.01));
    BacktestConfig cfg = BacktestConfig.hourlyDefaults();

    CalibrationHarness.PurgedCalibrationRun result =
        CalibrationHarness.runPurged(
            candles,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            space.grid(),
            cfg,
            3,
            2,
            0,
            null,
            null,
            100,
            FoldRunner.defaultOhlcv(),
            ScoringFunction.MEDIAN_CV_SHARPE,
            true,
            5); // embargoBars=5

    assertThat(result.run().survivors()).isNotEmpty();
    // 3 folds, warmupBars=100: folds 1 and 2 have a full 100-bar prefix each, fold 0 has none --
    // purge = (0) + (100+5) + (100+5) = 210, strictly greater than 3 x embargoBars=15 alone would
    // give, proving the prefix-contamination component is actually being counted, not just the
    // embargo.
    assertThat(result.purgeReport().totalPurgedBars()).isGreaterThan(15);
    assertThat(result.purgeReport().purgeFraction()).isBetween(0.0, 1.0);
  }

  @Test
  void runPurged_isDeterministic_sameInputsTwiceGiveIdenticalRanking() throws Exception {
    Path dataset = Path.of("src/test/resources/fixtures/large_snippets/BTCUSDT_1h_month.csv");
    List<Candle> candles;
    try (var stream = CsvKlineReader.stream(dataset.toAbsolutePath())) {
      candles = stream.toList();
    }
    EmaRsiParameterSpace space =
        new EmaRsiParameterSpace(
            new IntRange(9, 9, 1),
            new IntRange(21, 25, 2),
            new IntRange(14, 14, 1),
            new DoubleRange(70.0, 70.0, 1.0),
            new DoubleRange(30.0, 30.0, 1.0),
            new DoubleRange(0.01, 0.01, 0.01));
    BacktestConfig cfg = BacktestConfig.hourlyDefaults();

    CalibrationHarness.PurgedCalibrationRun run1 =
        CalibrationHarness.runPurged(
            candles,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            space.grid(),
            cfg,
            3,
            2,
            0,
            null,
            null,
            100,
            FoldRunner.defaultOhlcv(),
            ScoringFunction.CARRY_YIELD,
            false,
            5);
    CalibrationHarness.PurgedCalibrationRun run2 =
        CalibrationHarness.runPurged(
            candles,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            space.grid(),
            cfg,
            3,
            2,
            0,
            null,
            null,
            100,
            FoldRunner.defaultOhlcv(),
            ScoringFunction.CARRY_YIELD,
            false,
            5);

    assertThat(run1.run().survivors()).isEqualTo(run2.run().survivors());
    assertThat(run1.purgeReport()).isEqualTo(run2.purgeReport());
  }

  // ── PLAN-009 Task D: warm-up prefix + END_OF_DATA exclusion ─────────────────────────────────

  @Test
  void warmupPrefix_givesLaterFoldsRealEvaluationOpportunityDespiteTinyChunks() {
    // 10 folds x 15 bars/fold = 150 candles, warmupBars=20 -- every single fold's OWN chunk
    // (15 bars) is smaller than warmupBars, so the pre-Task-D model would have skipped every
    // fold outright (chunk.size() <= warmupBars), producing zero trades/zero Sharpe everywhere.
    // With a 20-bar prefix, folds 1-9 borrow enough history from immediately before their own
    // start to warm up before their own bars even begin, and a strictly-ascending price series
    // means any position opened is unconditionally profitable -- so a non-zero median Sharpe is
    // only possible if at least some folds actually got to evaluate and trade.
    List<Candle> candles = ascendingTrend(150);
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 20, BigDecimal.ONE, null, null, 8760);
    Candidate candidate =
        new Candidate(new AlwaysBuyStrategy(), java.util.Map.of("test", "alwaysBuy"));

    CalibrationRun run =
        CalibrationHarness.run(
            candles,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            List.of(candidate).stream(),
            cfg,
            10,
            1,
            0,
            null,
            null,
            100);

    assertThat(run.survivors()).hasSize(1);
    // Assert on total return, not Sharpe: each fold spans well under a day of hourly bars, and
    // Metrics' day-resampled Sharpe needs >= 2 daily observations to be non-degenerate regardless
    // of whether real trading happened -- that is a property of the day-resampling methodology at
    // short fold lengths (Task D's own motivating example), not something this test is about.
    // Total return has no such floor: ascending prices + any real evaluation window ⇒ strictly
    // positive return; zero would mean every fold was still being skipped despite the prefix.
    assertThat(run.survivors().get(0).cvTotalReturnMedian().signum()).isPositive();
  }

  @Test
  void endOfDataTrades_excludedFromFoldFieldTradeCount_butNotFromFoldReturn() {
    // AlwaysBuyStrategy opens exactly one position per fold and never sells -- every such trade
    // closes via END_OF_DATA at the fold's own last bar, which PLAN-009 Task D excludes from
    // cvTradeCountMedian (a fold-boundary artifact, not a real strategy decision) while the
    // fold's REAL price-driven return/Sharpe still reflects the position's actual mark-to-market
    // path -- excluding the trade from the count must not zero out the return too.
    List<Candle> candles = ascendingTrend(100); // 5 folds x 20 bars, warmupBars=10
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 10, BigDecimal.ONE, null, null, 8760);
    Candidate candidate =
        new Candidate(new AlwaysBuyStrategy(), java.util.Map.of("test", "alwaysBuy"));

    CalibrationRun run =
        CalibrationHarness.run(
            candles,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            List.of(candidate).stream(),
            cfg,
            5,
            1,
            0,
            null,
            null,
            100);

    assertThat(run.survivors()).hasSize(1);
    CalibrationResult result = run.survivors().get(0);
    assertThat(result.cvTradeCountMedian()).isEqualTo(0); // the only trade per fold is END_OF_DATA
    // Real price path still counts even though the trade itself is excluded (see the Sharpe-vs-
    // day-resampling note in warmupPrefix_givesLaterFoldsRealEvaluationOpportunityDespiteTinyChunks
    // for why total return, not Sharpe, is the assertion here).
    assertThat(result.cvTotalReturnMedian().signum()).isPositive();
  }

  // ── PLAN-009 Task D helpers ──────────────────────────────────────────────────────────────────

  /**
   * Strictly ascending close price, 1h apart -- any long position opened is unconditionally
   * profitable, so a non-zero result proves real trading happened.
   */
  private static List<Candle> ascendingTrend(int n) {
    List<Candle> out = new ArrayList<>(n);
    Instant t = Instant.parse("2024-01-01T00:00:00Z");
    for (int i = 0; i < n; i++) {
      double close = 100.0 + i;
      out.add(
          new Candle(
              t.plusSeconds(3600L * i),
              BigDecimal.valueOf(close),
              BigDecimal.valueOf(close + 0.1),
              BigDecimal.valueOf(close - 0.1),
              BigDecimal.valueOf(close),
              BigDecimal.valueOf(1000)));
    }
    return out;
  }

  /**
   * Stateless: always signals BUY, regardless of call history -- safe to reuse across folds (unlike
   * a call-counter-based scripted strategy, which would leak state between folds since {@link
   * Candidate#strategy()} is the same instance every fold). The harness's own already-holding check
   * no-ops repeat BUYs, so this opens exactly one position per fold run.
   */
  private static final class AlwaysBuyStrategy implements TradingStrategy {
    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
      return Optional.of(
          new TechnicalSignal(
              ctx.symbol(), Direction.BUY, 0.8, List.of(), "always buy", ctx.asOf()));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("AlwaysBuy", "0.0.1", "PLAN-009 Task D test-only");
    }
  }

  /**
   * Stateless (NFR-7): HOLD (empty signal) for any candle before {@code activeFrom}; from then on,
   * alternates BUY/SELL purely as a function of {@code ctx.asOf()}'s parity in hours since {@code
   * activeFrom} — deterministic without any mutable counter, so real round-trip trades concentrate
   * in whichever fold(s) fall after the threshold and zero trades occur in any fold entirely before
   * it (PLAN-013 Task D's total-vs-median trade-count filter test).
   */
  private static final class BurstFromBarStrategy implements TradingStrategy {
    private final Instant activeFrom;

    BurstFromBarStrategy(int startBar) {
      this.activeFrom = Instant.parse("2024-01-01T00:00:00Z").plusSeconds(3600L * startBar);
    }

    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
      Instant asOf = ctx.asOf();
      if (asOf.isBefore(activeFrom)) {
        return Optional.empty();
      }
      long hoursSinceActive = Duration.between(activeFrom, asOf).toHours();
      Direction dir = hoursSinceActive % 2 == 0 ? Direction.BUY : Direction.SELL;
      return Optional.of(new TechnicalSignal(ctx.symbol(), dir, 0.8, List.of(), "burst", asOf));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("BurstFromBar", "0.0.1", "PLAN-013 Task D test-only");
    }
  }
}
