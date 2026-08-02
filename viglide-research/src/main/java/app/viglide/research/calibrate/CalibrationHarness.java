package app.viglide.research.calibrate;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.BacktestResult;
import app.viglide.core.backtest.EconomicMetrics;
import app.viglide.core.backtest.EquityPoint;
import app.viglide.core.backtest.ExitReason;
import app.viglide.core.backtest.Metrics;
import app.viglide.core.backtest.SharpeStats;
import app.viglide.core.backtest.Trade;
import app.viglide.core.calibrate.Candidate;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.indicator.IndicatorMath;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Walk-forward calibration driver. Splits the candle history into K sequential folds and evaluates
 * every {@link Candidate} on every fold in parallel. Headline ranking metric is the median
 * cross-validation Sharpe across folds (PLAN-008 Task D.2: these folds are all inside the
 * calibration run's own dataset — selection optimises against them, so they are not a genuine
 * held-out out-of-sample test; see {@link CalibrationResult}).
 *
 * <p>Anti-overfitting: candidates whose median trade count across folds falls below {@code
 * minTrades} are removed from the ranking — a tiny sample of trades makes Sharpe meaningless.
 *
 * <p>Determinism: for a fixed candle list, fold split, and (for random search) seed, the output
 * ranking is byte-identical across runs (NFR-7). Parallelism does not affect determinism because
 * every evaluation is a pure function of (candidate, fold).
 *
 * <p>Generic over strategy family — see {@link Candidate}. The caller is responsible for building
 * the stream of {@code Candidate}s (e.g. via {@link EmaRsiParameterSpace} or sibling spaces).
 */
public final class CalibrationHarness {

  private CalibrationHarness() {}

  /**
   * Runs the calibration loop and returns the survivors ranked by median CV Sharpe descending —
   * pre-PLAN-013 behaviour, preserved byte-identically: delegates to {@link
   * ScoringFunction#MEDIAN_CV_SHARPE} and the legacy median-trade-count filter. Use the {@link
   * ScoringFunction} overload below for PLAN-013 Task D's {@code CARRY_YIELD} objective.
   */
  public static CalibrationRun run(
      List<Candle> candles,
      String symbol,
      CandleInterval interval,
      Stream<Candidate> candidates,
      BacktestConfig cfg,
      int folds,
      int parallelism,
      int minTrades,
      Duration timeBudget,
      Consumer<String> progress,
      int checkpointEvery) {
    return run(
        candles,
        symbol,
        interval,
        candidates,
        cfg,
        folds,
        parallelism,
        minTrades,
        timeBudget,
        progress,
        checkpointEvery,
        FoldRunner.defaultOhlcv());
  }

  /**
   * Funding-aware overload — caller supplies the {@link FoldRunner} so the harness can route
   * funding-arbitrage strategies through {@code FundingArbHarness} instead of the default OHLCV
   * path. Pre-PLAN-013 behaviour, preserved byte-identically — see the {@link ScoringFunction}
   * overload below for the current recommended objective.
   */
  public static CalibrationRun run(
      List<Candle> candles,
      String symbol,
      CandleInterval interval,
      Stream<Candidate> candidates,
      BacktestConfig cfg,
      int folds,
      int parallelism,
      int minTrades,
      Duration timeBudget,
      Consumer<String> progress,
      int checkpointEvery,
      FoldRunner foldRunner) {
    return run(
        candles,
        symbol,
        interval,
        candidates,
        cfg,
        folds,
        parallelism,
        minTrades,
        timeBudget,
        progress,
        checkpointEvery,
        foldRunner,
        ScoringFunction.MEDIAN_CV_SHARPE,
        true); // legacyMedianTradeCountFilter=true: exactly the pre-PLAN-013 filter semantics.
  }

  /**
   * Full overload (PLAN-013 Task D, review finding F4): {@code scoringFunction} ranks survivors
   * (descending) instead of a hardcoded {@code cvSharpeMedian} comparator — see {@link
   * ScoringFunction#CARRY_YIELD} for the objective ADR-0016 recommends going forward. {@code
   * legacyMedianTradeCountFilter}: {@code true} filters on {@code cvTradeCountMedian >= minTrades}
   * (the pre-PLAN-013 behaviour, near-inert for a 1–2-trades-per-fold strategy — kept reachable so
   * historical notes stay reproducible); {@code false} (the new recommended default) filters on
   * {@code cvTradeCountTotal >= minTrades}.
   *
   * <p>Ties are broken by a stable serialisation of the parameter map, never by hashmap iteration
   * order or insertion order — determinism (NFR-7) requires the ranking to be a pure function of
   * (candidates, folds, seed), and hashmap-order tie-breaking is not reproducible across JVM runs.
   */
  public static CalibrationRun run(
      List<Candle> candles,
      String symbol,
      CandleInterval interval,
      Stream<Candidate> candidates,
      BacktestConfig cfg,
      int folds,
      int parallelism,
      int minTrades,
      Duration timeBudget,
      Consumer<String> progress,
      int checkpointEvery,
      FoldRunner foldRunner,
      ScoringFunction scoringFunction,
      boolean legacyMedianTradeCountFilter) {

    // PLAN-009 Task D: each fold also carries a warm-up prefix (up to warmupBars candles taken
    // from immediately before its own start) so indicators warm up before the fold begins instead
    // of burning the fold's own first warmupBars candles on a cold start. warmupBars is constant
    // across every candidate in a run (no parameter space overrides it — only minHoldBars and
    // similar are swept), so it is safe to size the prefix once here, outside the per-candidate
    // loop below.
    List<FoldSplitter.FoldWindow> chunks =
        FoldSplitter.splitWithPrefix(candles, folds, cfg.warmupBars());
    return runWithChunks(
        chunks,
        symbol,
        interval,
        candidates,
        cfg,
        parallelism,
        minTrades,
        timeBudget,
        progress,
        checkpointEvery,
        foldRunner,
        scoringFunction,
        legacyMedianTradeCountFilter);
  }

  /**
   * Purged variant (PLAN-013 Task F, review finding F7): identical to the {@link ScoringFunction}
   * overload above, except fold boundaries come from {@link FoldSplitter#splitPurged} instead of
   * {@link FoldSplitter#splitWithPrefix} — {@code embargoBars} beyond the prefix contamination
   * {@code splitPurged} already accounts for (0 purges only that contamination, reported below,
   * never "purges nothing" — see {@link FoldSplitter#splitPurged}'s own Javadoc). Each {@link
   * FoldSplitter.PurgedFoldWindow} is repackaged into an equivalent {@link FoldSplitter.FoldWindow}
   * with the purged candles moved from {@code chunk} into {@code prefix} — the combined
   * prefix+chunk sequence {@link #evaluateAcrossFolds} sees is byte-identical either way, only the
   * scoring boundary moves, so the existing evaluation path needs zero changes (the plan's own "add
   * purging around the existing prefix/warm-up logic, do not restructure it" instruction).
   *
   * @return the ranked run, plus a {@link FoldSplitter.PurgeReport} — "a run that purges most of
   *     its data is telling you the fold count is too high for the lookback," not a silent no-op.
   */
  public record PurgedCalibrationRun(CalibrationRun run, FoldSplitter.PurgeReport purgeReport) {}

  public static PurgedCalibrationRun runPurged(
      List<Candle> candles,
      String symbol,
      CandleInterval interval,
      Stream<Candidate> candidates,
      BacktestConfig cfg,
      int folds,
      int parallelism,
      int minTrades,
      Duration timeBudget,
      Consumer<String> progress,
      int checkpointEvery,
      FoldRunner foldRunner,
      ScoringFunction scoringFunction,
      boolean legacyMedianTradeCountFilter,
      int embargoBars) {

    List<FoldSplitter.PurgedFoldWindow> purgedWindows =
        FoldSplitter.splitPurged(candles, folds, cfg.warmupBars(), embargoBars);
    FoldSplitter.PurgeReport purgeReport = FoldSplitter.purgeReport(purgedWindows);
    List<FoldSplitter.FoldWindow> chunks =
        purgedWindows.stream().map(CalibrationHarness::repackage).toList();

    CalibrationRun run =
        runWithChunks(
            chunks,
            symbol,
            interval,
            candidates,
            cfg,
            parallelism,
            minTrades,
            timeBudget,
            progress,
            checkpointEvery,
            foldRunner,
            scoringFunction,
            legacyMedianTradeCountFilter);
    return new PurgedCalibrationRun(run, purgeReport);
  }

  /**
   * Moves a {@link FoldSplitter.PurgedFoldWindow}'s purged candles from {@code chunk} into {@code
   * prefix} — {@code prefix' = prefix + chunk[0:purgedBars)}, {@code chunk' = chunk[purgedBars:]}.
   * {@code prefix' + chunk'} is exactly {@code prefix + chunk}, unchanged; only where the scoring
   * boundary falls moves, which is exactly what purging means here.
   */
  private static FoldSplitter.FoldWindow repackage(FoldSplitter.PurgedFoldWindow w) {
    List<Candle> newPrefix = new ArrayList<>(w.prefix());
    newPrefix.addAll(w.chunk().subList(0, w.purgedBars()));
    return new FoldSplitter.FoldWindow(newPrefix, w.scoreableChunk());
  }

  private static CalibrationRun runWithChunks(
      List<FoldSplitter.FoldWindow> chunks,
      String symbol,
      CandleInterval interval,
      Stream<Candidate> candidates,
      BacktestConfig cfg,
      int parallelism,
      int minTrades,
      Duration timeBudget,
      Consumer<String> progress,
      int checkpointEvery,
      FoldRunner foldRunner,
      ScoringFunction scoringFunction,
      boolean legacyMedianTradeCountFilter) {

    int folds = chunks.size();
    int workers =
        parallelism > 0 ? parallelism : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    ForkJoinPool pool = new ForkJoinPool(workers);

    AtomicLong evaluated = new AtomicLong();
    Instant start = Instant.now();
    List<CalibrationResult> results = new ArrayList<>();

    if (progress != null) {
      progress.accept(
          String.format(
              "[%s] started folds=%d workers=%d checkpointEvery=%d timeBudget=%s",
              Instant.now(), folds, workers, checkpointEvery, timeBudget));
    }

    try {
      pool.submit(
              () ->
                  candidates
                      .parallel()
                      .takeWhile(
                          c ->
                              timeBudget == null
                                  || Duration.between(start, Instant.now()).compareTo(timeBudget)
                                      < 0)
                      .map(c -> evaluateAcrossFolds(c, chunks, symbol, interval, cfg, foldRunner))
                      .forEach(
                          r -> {
                            synchronized (results) {
                              results.add(r);
                            }
                            long n = evaluated.incrementAndGet();
                            if (progress != null && n % checkpointEvery == 0) {
                              CalibrationResult best = currentBest(results, scoringFunction);
                              progress.accept(progressLine(start, n, best, scoringFunction));
                            }
                          }))
          .get();
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("calibration interrupted", ie);
    } catch (java.util.concurrent.ExecutionException ee) {
      throw new RuntimeException(ee.getCause());
    } finally {
      pool.shutdown();
    }

    if (progress != null) {
      long n = evaluated.get();
      CalibrationResult best = currentBest(results, scoringFunction);
      progress.accept(progressLine(start, n, best, scoringFunction));
      progress.accept(
          String.format(
              "[%s] finished evaluated=%d elapsed=%ds",
              Instant.now(), n, Duration.between(start, Instant.now()).toSeconds()));
    }

    // PLAN-008 Task D.1: trial accounting over EVERY evaluated candidate (not just survivors) —
    // --time-budget truncation makes this count authoritative, so read it straight off the
    // materialised list rather than re-deriving it from the (possibly time-truncated) stream.
    int trials;
    double trialVariance;
    List<CalibrationResult> survivors = new ArrayList<>();
    synchronized (results) {
      trials = results.size();
      trialVariance = trialSharpeVariancePerPeriod(results);
      for (CalibrationResult r : results) {
        int count = legacyMedianTradeCountFilter ? r.cvTradeCountMedian() : r.cvTradeCountTotal();
        if (count >= minTrades) survivors.add(r);
      }
    }
    // PLAN-013 Task D: ranked by the injected scoringFunction, descending; ties broken by a stable
    // serialisation of the parameter map (never hashmap iteration/insertion order — NFR-7).
    survivors.sort(
        Comparator.comparingDouble(scoringFunction::score)
            .reversed()
            .thenComparing(r -> stableParamsKey(r.params())));
    return new CalibrationRun(survivors, trials, trialVariance);
  }

  /**
   * Sorted {@code key=value} pairs joined by {@code ,} — order-independent of the source map's own
   * iteration order, so sort ties break the same way regardless of hashmap layout (NFR-7).
   */
  private static String stableParamsKey(Map<String, Object> params) {
    return params.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> e.getKey() + "=" + e.getValue())
        .collect(java.util.stream.Collectors.joining(","));
  }

  /** Population variance (1/n) of the evaluated candidates' per-period Sharpe ratios. */
  private static double trialSharpeVariancePerPeriod(List<CalibrationResult> results) {
    int n = results.size();
    if (n < 2) return 0.0;
    double mean = 0.0;
    for (CalibrationResult r : results) {
      mean += r.cvSharpeMedian() / SharpeStats.ANNUALISATION_FACTOR;
    }
    mean /= n;
    double sumSq = 0.0;
    for (CalibrationResult r : results) {
      double d = (r.cvSharpeMedian() / SharpeStats.ANNUALISATION_FACTOR) - mean;
      sumSq += d * d;
    }
    return sumSq / n;
  }

  // ── per-candidate evaluation ─────────────────────────────────────────────────────────────────

  /**
   * Package-visible (not private) so {@link PlateauScorer} (PLAN-009 Task E) can score a perturbed
   * neighbor candidate using the exact same fold-evaluation path as the original ranking — same
   * warm-up prefix, same END_OF_DATA exclusion (PLAN-009 Task D).
   */
  static CalibrationResult evaluateAcrossFolds(
      Candidate c,
      List<FoldSplitter.FoldWindow> chunks,
      String symbol,
      CandleInterval interval,
      BacktestConfig cfg,
      FoldRunner foldRunner) {

    double[] sharpes = new double[chunks.size()];
    BigDecimal[] returns = new BigDecimal[chunks.size()];
    BigDecimal[] drawdowns = new BigDecimal[chunks.size()];
    int[] tradeCounts = new int[chunks.size()];
    double[] ulcerIndexes = new double[chunks.size()];

    // PLAN-013 Task D (finding F4): pooled across every fold's own trades, not a median of
    // per-fold ratios -- most folds evaluate too few trades for a per-fold ratio to be meaningful
    // (the same problem the pooling is meant to fix, restated per-fold instead of solved).
    BigDecimal pooledNetPnl = BigDecimal.ZERO;
    BigDecimal pooledDeployedCapitalDays = BigDecimal.ZERO;

    // PLAN-008 Task F: per-candidate harness knobs (e.g. minHoldBars) applied once, shared by
    // every fold this candidate runs against.
    BacktestConfig candidateCfg = c.configOverride().apply(cfg);

    for (int i = 0; i < chunks.size(); i++) {
      FoldSplitter.FoldWindow window = chunks.get(i);
      if (window.chunk().isEmpty()) {
        sharpes[i] = 0.0;
        returns[i] = BigDecimal.ZERO;
        drawdowns[i] = BigDecimal.ZERO;
        tradeCounts[i] = 0;
        ulcerIndexes[i] = 0.0;
        continue;
      }
      // PLAN-009 Task D: run on prefix+chunk combined so indicators are warm by the fold's own
      // first bar, then re-derive metrics from ONLY the fold's own window — excluding (a) any
      // equity/point contributed by the prefix itself and (b) END_OF_DATA-reason trades, which are
      // a fold-boundary artifact (a forced liquidation at the fold's last bar, not a real strategy
      // exit) rather than excluding trades from a continuous run that would happen to close there
      // anyway.
      List<Candle> combined = new ArrayList<>(window.prefix().size() + window.chunk().size());
      combined.addAll(window.prefix());
      combined.addAll(window.chunk());
      BacktestResult r = foldRunner.run(c.strategy(), combined, symbol, interval, candidateCfg);

      Instant foldStart = window.chunk().get(0).openTime();
      List<EquityPoint> foldEquity =
          r.equityCurve().stream().filter(p -> !p.at().isBefore(foldStart)).toList();
      List<Trade> foldTrades =
          r.trades().stream()
              .filter(t -> t.exitReason() != ExitReason.END_OF_DATA)
              .filter(t -> !t.entryTime().isBefore(foldStart))
              .toList();

      sharpes[i] = Metrics.annualisedSharpe(foldEquity);
      returns[i] = foldReturn(foldEquity);
      drawdowns[i] = Metrics.maxDrawdown(foldEquity);
      tradeCounts[i] = foldTrades.size();
      ulcerIndexes[i] = EconomicMetrics.ulcerIndex(foldEquity);

      for (Trade t : foldTrades) {
        pooledNetPnl = pooledNetPnl.add(t.pnl(), IndicatorMath.MC);
      }
      pooledDeployedCapitalDays =
          pooledDeployedCapitalDays.add(
              EconomicMetrics.deployedCapitalDays(foldTrades), IndicatorMath.MC);
    }

    double medianSharpe = medianDouble(sharpes.clone());
    BigDecimal medianReturn = medianBig(returns.clone());
    BigDecimal worstDd = maxBig(drawdowns);
    int medianTrades = medianInt(tradeCounts.clone());
    int totalTrades = sumInt(tradeCounts);
    double medianUlcer = medianDouble(ulcerIndexes.clone());
    BigDecimal pooledReturnOnDeployedCapital =
        pooledDeployedCapitalDays.signum() == 0
            ? BigDecimal.ZERO
            : pooledNetPnl
                .divide(pooledDeployedCapitalDays, IndicatorMath.MC)
                .multiply(DAYS_PER_YEAR, IndicatorMath.MC);

    return new CalibrationResult(
        c.paramsSnapshot(),
        medianSharpe,
        medianReturn,
        worstDd,
        medianTrades,
        chunks.size(),
        totalTrades,
        pooledReturnOnDeployedCapital,
        medianUlcer);
  }

  private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365L);

  private static int sumInt(int[] xs) {
    int sum = 0;
    for (int x : xs) sum += x;
    return sum;
  }

  /**
   * Fold-scoped return: first vs. last equity point <em>within the fold's own window</em> — not
   * relative to {@code cfg.startingCash()}, since a later fold's prefix may have already moved cash
   * away from the original starting balance before the fold itself even begins.
   */
  private static BigDecimal foldReturn(List<EquityPoint> foldEquity) {
    if (foldEquity.isEmpty()) return BigDecimal.ZERO;
    BigDecimal first = foldEquity.get(0).equity();
    BigDecimal last = foldEquity.get(foldEquity.size() - 1).equity();
    if (first.signum() == 0) return BigDecimal.ZERO;
    return last.subtract(first, IndicatorMath.MC).divide(first, IndicatorMath.MC);
  }

  // ── math helpers ─────────────────────────────────────────────────────────────────────────────

  private static double medianDouble(double[] xs) {
    Arrays.sort(xs);
    int n = xs.length;
    return n % 2 == 1 ? xs[n / 2] : (xs[n / 2 - 1] + xs[n / 2]) / 2.0;
  }

  private static int medianInt(int[] xs) {
    Arrays.sort(xs);
    int n = xs.length;
    return n % 2 == 1 ? xs[n / 2] : (xs[n / 2 - 1] + xs[n / 2]) / 2;
  }

  private static BigDecimal medianBig(BigDecimal[] xs) {
    Arrays.sort(xs);
    int n = xs.length;
    if (n % 2 == 1) return xs[n / 2];
    return xs[n / 2 - 1].add(xs[n / 2]).divide(BigDecimal.valueOf(2));
  }

  private static BigDecimal maxBig(BigDecimal[] xs) {
    BigDecimal m = xs[0];
    for (BigDecimal x : xs) if (x.compareTo(m) > 0) m = x;
    return m;
  }

  private static CalibrationResult currentBest(
      List<CalibrationResult> results, ScoringFunction scoringFunction) {
    synchronized (results) {
      CalibrationResult best = null;
      double bestScore = Double.NEGATIVE_INFINITY;
      for (CalibrationResult r : results) {
        double score = scoringFunction.score(r);
        if (best == null || score > bestScore) {
          best = r;
          bestScore = score;
        }
      }
      return best;
    }
  }

  private static String progressLine(
      Instant start, long evaluated, CalibrationResult best, ScoringFunction scoringFunction) {
    String bestStr =
        best == null ? "n/a" : String.format("%.5f %s", scoringFunction.score(best), best.params());
    long elapsedSec = Duration.between(start, Instant.now()).toSeconds();
    return String.format(
        "[%s] evaluated=%d elapsed=%ds best=%s", Instant.now(), evaluated, elapsedSec, bestStr);
  }
}
