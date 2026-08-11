package app.viglide.research.calibrate;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.BacktestResult;
import app.viglide.core.backtest.EconomicMetrics;
import app.viglide.core.backtest.PortfolioBacktestHarness;
import app.viglide.core.calibrate.Candidate;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.risk.BacktestClockSync;
import app.viglide.core.risk.MutableClock;
import app.viglide.core.risk.RiskManager;
import app.viglide.core.risk.RiskManagerPort;
import app.viglide.core.risk.RiskParameters;
import app.viglide.core.spi.TradingStrategy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Pooled panel fit across pairs (PLAN-013 Task G, review finding F4) — the highest-value item in
 * the plan. {@link CalibrationHarness} fits one parameter vector <em>per pair per year</em>: ~60
 * independent vectors, each drawn 300× from a 288,000-cell grid, each validated on a handful of
 * trades (the ablation's own finding: "zero cells anywhere in the grid where both calibrated and
 * BASE-1 traded ≥ 10 times"). This harness evaluates each candidate against <strong>every pair
 * simultaneously</strong> and scores the pooled result — converting ~60 fits on 1–2 trades each
 * into one fit on a several-hundred-trade panel, the only way the resulting OOS test (PLAN-016) is
 * a real test and DSR's trial correction (Task H) is meaningful.
 *
 * <p><strong>Reuses {@link PortfolioBacktestHarness} as the per-candidate evaluation
 * engine</strong> (per the plan's own instruction — its union-of-timestamps-plus-per-symbol-skip
 * iteration already tolerates different pairs having different history lengths and data gaps
 * correctly; reinventing that here would be a second, divergent implementation of the same subtle
 * logic). Every symbol gets the <em>same</em> strategy instance, built from the candidate's
 * <em>same</em> parameter set — that identity is what "one parameter vector across the whole panel"
 * means concretely. Shared cash/RM gating across symbols is a deliberate feature, not an artifact:
 * it evaluates the candidate under the same portfolio-level constraints (leverage, drawdown circuit
 * breaker) it would actually face if deployed, rather than pretending each pair has its own private
 * capital.
 *
 * <p><strong>Weighting (the plan's own trap):</strong> {@link
 * PanelResult#pooledReturnOnDeployedCapital} is computed from the panel's pooled trades via {@link
 * EconomicMetrics}, which weights by capital-days, not by pair count — a pair contributing one
 * 90-day trade counts far more than one contributing a single 8-hour trade, regardless of how many
 * pairs are in the universe. {@link PanelResult#crossPairSignConsistency} is deliberately a
 * <em>separate</em>, unweighted-by-size count of pairs, so a candidate cannot buy a high
 * consistency score by including many thin, barely-traded pairs — {@link
 * PanelResult#pairsWithTrades} makes that visible directly.
 */
public final class PanelCalibrationHarness {

  private PanelCalibrationHarness() {}

  /**
   * One candidate's pooled panel outcome. {@code crossPairSignConsistency} = (pairs with positive
   * pooled PnL) / (pairs that traded at all) — a pair that never traded contributes to neither the
   * numerator nor the denominator (it is neither a win nor a loss, it is silence; see {@link
   * PanelResult#pairsWithTrades} and {@code totalPairs} to see how much of the universe that was).
   */
  public record PanelResult(
      Map<String, Object> params,
      int pooledTradeCount,
      BigDecimal pooledNetPnl,
      BigDecimal pooledReturnOnDeployedCapital,
      double crossPairSignConsistency,
      int pairsWithTrades,
      int totalPairs,
      Map<String, BigDecimal> pnlByPair,
      Map<String, Long> tradeCountByPair) {

    /**
     * Panel objective (PLAN-013 Task G): pooled economic yield, penalised by how few pairs actually
     * agreed with it — a candidate winning hugely on one pair and losing on the rest scores far
     * below one with a smaller but broadly-shared edge, operationalising the plan's own "a
     * candidate that wins on 10 of 12 pairs is worth far more than one that wins hugely on one."
     */
    public double score() {
      return pooledReturnOnDeployedCapital.doubleValue() * crossPairSignConsistency;
    }
  }

  /**
   * @param candlesBySymbol every pair's own candle history — different lengths and gaps are
   *     expected and handled by {@link PortfolioBacktestHarness}, not this harness
   * @param fundingBySymbol funding events per pair; empty (or absent) for a non-funding-aware
   *     strategy family
   * @param volatilityScaleBySymbol PLAN-013 Task G item 2: optional per-pair threshold scaling by
   *     that pair's own funding volatility, expressed as a single multiplier per symbol (empty map
   *     ⇒ off, the default — enable only if the pooled fit is clearly hurt by pair heterogeneity).
   *     When present, {@code candidateBuilder} receives the pair's own scale factor so it can
   *     widen/narrow that one pair's entry threshold; every other aspect of the candidate stays
   *     identical across pairs — this is the "one extra global parameter, not one per pair" the
   *     plan calls for. {@code 1.0} for a symbol not present in the map (no scaling).
   * @param candidateBuilder builds the per-pair strategy instance for one base candidate — receives
   *     the base candidate and that symbol's volatility scale factor (1.0 when scaling is off); for
   *     strategies that ignore scaling entirely, this is just {@code (base, scale) ->
   *     base.strategy()}
   */
  public static List<PanelResult> run(
      Map<String, List<Candle>> candlesBySymbol,
      Map<String, List<FundingEvent>> fundingBySymbol,
      Map<String, Double> volatilityScaleBySymbol,
      CandleInterval interval,
      BacktestConfig cfg,
      Stream<Candidate> candidates,
      java.util.function.BiFunction<Candidate, Double, TradingStrategy> candidateBuilder,
      int parallelism,
      Duration timeBudget,
      Consumer<String> progress,
      int checkpointEvery) {
    Objects.requireNonNull(candlesBySymbol, "candlesBySymbol");
    Objects.requireNonNull(fundingBySymbol, "fundingBySymbol");
    Objects.requireNonNull(volatilityScaleBySymbol, "volatilityScaleBySymbol");
    java.util.Set<String> symbols = candlesBySymbol.keySet();
    if (symbols.isEmpty()) {
      throw new IllegalArgumentException("candlesBySymbol must not be empty");
    }

    int workers =
        parallelism > 0 ? parallelism : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    ForkJoinPool pool = new ForkJoinPool(workers);
    AtomicLong evaluated = new AtomicLong();
    Instant start = Instant.now();
    List<PanelResult> results = new ArrayList<>();

    if (progress != null) {
      progress.accept(
          String.format(
              "[%s] panel started pairs=%d workers=%d checkpointEvery=%d timeBudget=%s",
              Instant.now(), symbols.size(), workers, checkpointEvery, timeBudget));
    }

    // PLAN-016 Task C (NFR-7): same fix, same reason as CalibrationHarness -- see the long comment
    // there. Providers hand back Stream.generate(...) over one shared java.util.Random, which is
    // documented unordered and drew a different parameter set on every run of the same seed once
    // .parallel() raced several workers over it. PortfolioCalibrationHarness was never exposed:
    // PLAN-019 Task D took a List<PortfolioCandidate> rather than a Stream, which is exactly the
    // property that saved it.
    List<Candidate> orderedCandidates = candidates.toList();

    try {
      pool.submit(
              () ->
                  orderedCandidates.parallelStream()
                      .takeWhile(
                          c ->
                              timeBudget == null
                                  || Duration.between(start, Instant.now()).compareTo(timeBudget)
                                      < 0)
                      .map(
                          c ->
                              evaluateOnPanel(
                                  c,
                                  candlesBySymbol,
                                  fundingBySymbol,
                                  volatilityScaleBySymbol,
                                  interval,
                                  cfg,
                                  candidateBuilder))
                      .forEach(
                          r -> {
                            synchronized (results) {
                              results.add(r);
                            }
                            long n = evaluated.incrementAndGet();
                            if (progress != null && n % checkpointEvery == 0) {
                              progress.accept(
                                  String.format(
                                      "[%s] panel evaluated=%d elapsed=%ds",
                                      Instant.now(),
                                      n,
                                      Duration.between(start, Instant.now()).toSeconds()));
                            }
                          }))
          .get();
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("panel calibration interrupted", ie);
    } catch (java.util.concurrent.ExecutionException ee) {
      throw new RuntimeException(ee.getCause());
    } finally {
      pool.shutdown();
    }

    if (progress != null) {
      progress.accept(
          String.format(
              "[%s] panel finished evaluated=%d elapsed=%ds",
              Instant.now(), evaluated.get(), Duration.between(start, Instant.now()).toSeconds()));
    }

    List<PanelResult> ranked = new ArrayList<>(results);
    ranked.sort(
        Comparator.comparingDouble(PanelResult::score)
            .reversed()
            .thenComparing(r -> stableParamsKey(r.params())));
    return List.copyOf(ranked);
  }

  private static PanelResult evaluateOnPanel(
      Candidate base,
      Map<String, List<Candle>> candlesBySymbol,
      Map<String, List<FundingEvent>> fundingBySymbol,
      Map<String, Double> volatilityScaleBySymbol,
      CandleInterval interval,
      BacktestConfig cfg,
      java.util.function.BiFunction<Candidate, Double, TradingStrategy> candidateBuilder) {

    Map<String, TradingStrategy> strategiesBySymbol = new LinkedHashMap<>();
    for (String symbol : candlesBySymbol.keySet()) {
      double scale = volatilityScaleBySymbol.getOrDefault(symbol, 1.0);
      strategiesBySymbol.put(symbol, candidateBuilder.apply(base, scale));
    }

    BacktestConfig candidateCfg = base.configOverride().apply(cfg);
    MutableClock clock = new MutableClock(Instant.EPOCH, ZoneOffset.UTC);
    RiskManagerPort rm =
        new BacktestClockSync(new RiskManager(RiskParameters.defaults(), clock), clock);

    BacktestResult result =
        PortfolioBacktestHarness.run(
            candlesBySymbol, strategiesBySymbol, fundingBySymbol, interval, candidateCfg, rm);

    Map<String, BigDecimal> pnlByPair = new LinkedHashMap<>();
    Map<String, Long> tradeCountByPair = new LinkedHashMap<>();
    int pairsWithTrades = 0;
    int pairsPositive = 0;
    for (String symbol : candlesBySymbol.keySet()) {
      BigDecimal pnl =
          (BigDecimal) result.diagnostics().getOrDefault("pnl." + symbol, BigDecimal.ZERO);
      long tradeCount = (long) result.diagnostics().getOrDefault("trades." + symbol, 0L);
      pnlByPair.put(symbol, pnl);
      tradeCountByPair.put(symbol, tradeCount);
      if (tradeCount > 0) {
        pairsWithTrades++;
        if (pnl.signum() > 0) {
          pairsPositive++;
        }
      }
    }
    double crossPairSignConsistency =
        pairsWithTrades == 0 ? 0.0 : (double) pairsPositive / pairsWithTrades;

    BigDecimal pooledNetPnl = BigDecimal.ZERO;
    for (BigDecimal pnl : pnlByPair.values()) {
      pooledNetPnl = pooledNetPnl.add(pnl);
    }

    return new PanelResult(
        base.paramsSnapshot(),
        result.tradeCount(),
        pooledNetPnl,
        EconomicMetrics.returnOnDeployedCapital(result),
        crossPairSignConsistency,
        pairsWithTrades,
        candlesBySymbol.size(),
        Map.copyOf(pnlByPair),
        Map.copyOf(tradeCountByPair));
  }

  /** Same stable tie-break convention as {@link CalibrationHarness} (NFR-7). */
  private static String stableParamsKey(Map<String, Object> params) {
    return params.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> e.getKey() + "=" + e.getValue())
        .collect(java.util.stream.Collectors.joining(","));
  }
}
