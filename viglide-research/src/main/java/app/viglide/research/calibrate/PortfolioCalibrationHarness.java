package app.viglide.research.calibrate;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.BacktestResult;
import app.viglide.core.backtest.EconomicMetrics;
import app.viglide.core.backtest.EquityPoint;
import app.viglide.core.backtest.ExitReason;
import app.viglide.core.backtest.Metrics;
import app.viglide.core.backtest.PortfolioBacktestHarness;
import app.viglide.core.backtest.Trade;
import app.viglide.core.calibrate.PortfolioCandidate;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.indicator.IndicatorMath;
import app.viglide.core.risk.BacktestClockSync;
import app.viglide.core.risk.MutableClock;
import app.viglide.core.risk.RiskManager;
import app.viglide.core.risk.RiskManagerPort;
import app.viglide.core.risk.RiskParameters;
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
import java.util.function.Consumer;

/**
 * The calibration loop {@code CarryRankingStrategy} (and any future {@link
 * app.viglide.core.spi.PortfolioStrategy}) needs (PLAN-019 Task D). {@link CalibrationHarness} and
 * {@link PanelCalibrationHarness} both fit a {@link app.viglide.core.spi.TradingStrategy} — their
 * entry points need a single {@code symbol} argument (or, for the panel, a per-symbol {@code
 * TradingStrategy} instance) because that interface's own shape is single-symbol, single-timestamp.
 * A {@code PortfolioStrategy} fit needs {@code Map<String, List<Candle>> candlesBySymbol} and no
 * {@code symbol} argument at all — this class is that entry point, built around PLAN-019 Task C's
 * two-leg {@code runTargets}.
 *
 * <p><strong>Reuses every instrument PLAN-013 already shipped, rather than reimplementing any of
 * it:</strong> {@link PortfolioFoldSplitter#splitPurged} (this task's own cross-sectional
 * counterpart of {@link FoldSplitter#splitPurged}) for purged K-fold with embargo; {@link
 * TrialRegistry} for cumulative candidate accounting (integration proven by a test, not baked into
 * this class — the same layering {@link PanelCalibrationHarness} already uses, leaving the registry
 * call to whichever caller knows the dataset fingerprint); {@link PortfolioScoringFunction} for an
 * injectable ranking objective instead of a hardcoded one; {@code minTrades} defaulting to the K1′
 * floor is the caller's responsibility, same as {@link CalibrationHarness}.
 *
 * <p><strong>Deliberately sequential, not pooled/parallel like {@link PanelCalibrationHarness} or
 * {@link CalibrationHarness}</strong> — a disclosed scope simplification, not an oversight: the
 * search surface Task D actually needs ({@code k}, {@code windowSize}, {@code minFundingEvents},
 * {@code noTradeBand}) is small enough for a sequential loop to be practical, and parallelism was
 * not part of that task's stated acceptance criteria (still isn't — PLAN-022 Task A added {@code
 * timeBudget}/{@code checkpointEvery} for a single-shot overnight run's own sake, mirroring {@link
 * PanelCalibrationHarness}'s contract, without adding worker pooling). Determinism (NFR-7) holds
 * regardless — candidates are scored in input order and ranked with a stable tie-break, exactly
 * {@link PanelCalibrationHarness#run}'s own tie-break convention; a run truncated by {@code
 * timeBudget} after {@code n} candidates and resumed over the remainder ranks identically to the
 * same candidate list evaluated straight through, once the two partial results are merged and
 * re-ranked with {@link #rank}.
 *
 * <p><strong>Per-fold mechanics mirror {@link CalibrationHarness#evaluateAcrossFolds}
 * exactly,</strong> just cross-sectionally: each fold's {@code repackagedPrefixBySymbol() +
 * scoreableChunkBySymbol()} (combined, per symbol) is fed to {@link
 * PortfolioBacktestHarness#runTargets} so indicators are warm by the fold's own first scoreable
 * bar; the result is then re-scored against ONLY that fold's own scoreable window — excluding (a)
 * anything from the prefix and (b) {@link ExitReason#END_OF_DATA} trades, a fold-boundary artifact,
 * not a real strategy exit.
 */
public final class PortfolioCalibrationHarness {

  private PortfolioCalibrationHarness() {}

  private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365L);

  /**
   * @param candlesBySymbol perp (or, for a strategy with no carry-capable symbols, the single)
   *     decision-window series per symbol — the panel {@link PortfolioFoldSplitter#splitPurged}
   *     folds
   * @param fundingBySymbol funding events per symbol, ascending {@code time}
   * @param spotCandlesBySymbol spot klines for carry-capable symbols only, same contract as {@link
   *     PortfolioBacktestHarness}'s two-leg {@code runTargets} overload — empty (or a symbol
   *     absent) means that symbol can only ever be targeted {@code SPOT_ONLY}/{@code SPOT_LONG}
   * @param folds number of purged K-folds (K &ge; 2)
   * @param embargoBars extra bars purged beyond the nominal prefix-contamination length
   * @param minTrades survivors need at least this many pooled trades across all folds combined —
   *     pass the K1′ floor (30, ADR-0016 condition 2), not the legacy single-symbol default of 10
   * @param allocatedCapital fixed capital base every candidate's {@code targetWeight} is a fraction
   *     of, same meaning as {@link PortfolioBacktestHarness}'s {@code runTargets}
   * @param noTradeBand the harness-wide default notional no-trade band. A real parameter in this
   *     task's own search surface (PLAN-019 Task D) — to vary it per candidate (e.g. across a
   *     search grid), set {@link PortfolioCandidate#noTradeBand()} explicitly on that candidate; a
   *     non-null candidate value takes precedence over this default. Whichever value applied is
   *     echoed into every result's {@code params} under the reserved key {@code "noTradeBand"},
   *     overwriting any same-named entry in the candidate's own {@code paramsSnapshot}, so the
   *     winning row in {@link PortfolioCalibrationResult} is self-describing and cannot disagree
   *     with the run it describes (PLAN-021 Task B)
   * @param candidates every parameter set to evaluate, already built
   * @param scoringFunction ranks the ranked-descending output; {@link
   *     PortfolioScoringFunction#CARRY_YIELD} is the recommended default, mirroring {@link
   *     ScoringFunction#CARRY_YIELD}'s own F2 rationale
   */
  public static List<PortfolioCalibrationResult> run(
      Map<String, List<Candle>> candlesBySymbol,
      Map<String, List<FundingEvent>> fundingBySymbol,
      Map<String, List<Candle>> spotCandlesBySymbol,
      int folds,
      int embargoBars,
      int minTrades,
      CandleInterval interval,
      BacktestConfig cfg,
      BigDecimal allocatedCapital,
      BigDecimal noTradeBand,
      List<PortfolioCandidate> candidates,
      PortfolioScoringFunction scoringFunction) {
    return run(
        candlesBySymbol,
        fundingBySymbol,
        spotCandlesBySymbol,
        folds,
        embargoBars,
        minTrades,
        interval,
        cfg,
        allocatedCapital,
        noTradeBand,
        candidates,
        scoringFunction,
        RiskParameters.defaults());
  }

  /**
   * Same as the twelve-argument {@link #run} overload, plus an explicit {@link RiskParameters}
   * (PLAN-021 Task C) — that overload always used {@link RiskParameters#defaults()} ({@code
   * maxLeverage = 2.0}), which made a pre-registered non-default leverage (e.g. the 1× this plan's
   * own PLAN-016 pre-registration freezes for {@code CarryRankingStrategy}, per PLAN-019 Task A's
   * SURVIVABLE verdict) impossible to express. Leverage is not cosmetic here: it is the carry
   * margin divisor ({@link PortfolioBacktestHarness}'s two-leg {@code runTargets}), so it directly
   * sets the liquidation rate. The effective {@code maxLeverage} is echoed into every result's
   * {@code params} under the reserved key {@code "maxLeverage"}, overwriting any same-named caller
   * entry — same convention as {@code noTradeBand} — so a pre-registered constant that shaped the
   * run is auditable from the output alone.
   *
   * @param riskParameters risk parameters (including {@code maxLeverage}) used to build a fresh
   *     {@link RiskManager} for every fold
   */
  public static List<PortfolioCalibrationResult> run(
      Map<String, List<Candle>> candlesBySymbol,
      Map<String, List<FundingEvent>> fundingBySymbol,
      Map<String, List<Candle>> spotCandlesBySymbol,
      int folds,
      int embargoBars,
      int minTrades,
      CandleInterval interval,
      BacktestConfig cfg,
      BigDecimal allocatedCapital,
      BigDecimal noTradeBand,
      List<PortfolioCandidate> candidates,
      PortfolioScoringFunction scoringFunction,
      RiskParameters riskParameters) {
    return runInternal(
            candlesBySymbol,
            fundingBySymbol,
            spotCandlesBySymbol,
            folds,
            embargoBars,
            minTrades,
            interval,
            cfg,
            allocatedCapital,
            noTradeBand,
            candidates,
            scoringFunction,
            riskParameters,
            null,
            null,
            Integer.MAX_VALUE)
        .survivors();
  }

  /**
   * Same as the thirteen-argument {@link #run} overload, plus {@code timeBudget}/{@code
   * progress}/{@code checkpointEvery} (PLAN-022 Task A) — makes an overnight sweep against this
   * harness survivable the same way {@link PanelCalibrationHarness}'s own contract already is:
   * candidates stop being evaluated once {@code timeBudget} elapses (a cutoff surfaced as {@link
   * PortfolioCalibrationRun#trials()} being less than {@code candidates.size()}, never silently),
   * and every {@code checkpointEvery} candidates a progress line (current elapsed time and best
   * score so far) is emitted to {@code progress}. {@code timeBudget == null} never cuts the sweep
   * short, and {@code progress == null} emits nothing — both match the thirteen-argument overload's
   * behaviour exactly, so that overload's own determinism guarantees are unaffected.
   *
   * <p>Candidates are still evaluated strictly in input order with no parallelism (this harness's
   * own disclosed scope, see class Javadoc), so a sweep interrupted after {@code n} candidates and
   * resumed by calling this method again over {@code candidates.subList(n, candidates.size())}
   * produces, once the two survivor lists are merged and passed through {@link #rank}, the
   * byte-identical ranking of the same candidate list evaluated straight through (NFR-7).
   *
   * @param timeBudget wall-clock budget for the whole sweep, checked between candidates; {@code
   *     null} for no limit
   * @param progress receives one line at the start, one every {@code checkpointEvery} candidates,
   *     and one at the end; {@code null} to disable
   * @param checkpointEvery how often (in evaluated candidates) to emit a progress line
   */
  public static PortfolioCalibrationRun run(
      Map<String, List<Candle>> candlesBySymbol,
      Map<String, List<FundingEvent>> fundingBySymbol,
      Map<String, List<Candle>> spotCandlesBySymbol,
      int folds,
      int embargoBars,
      int minTrades,
      CandleInterval interval,
      BacktestConfig cfg,
      BigDecimal allocatedCapital,
      BigDecimal noTradeBand,
      List<PortfolioCandidate> candidates,
      PortfolioScoringFunction scoringFunction,
      RiskParameters riskParameters,
      Duration timeBudget,
      Consumer<String> progress,
      int checkpointEvery) {
    return runInternal(
        candlesBySymbol,
        fundingBySymbol,
        spotCandlesBySymbol,
        folds,
        embargoBars,
        minTrades,
        interval,
        cfg,
        allocatedCapital,
        noTradeBand,
        candidates,
        scoringFunction,
        riskParameters,
        timeBudget,
        progress,
        checkpointEvery);
  }

  private static PortfolioCalibrationRun runInternal(
      Map<String, List<Candle>> candlesBySymbol,
      Map<String, List<FundingEvent>> fundingBySymbol,
      Map<String, List<Candle>> spotCandlesBySymbol,
      int folds,
      int embargoBars,
      int minTrades,
      CandleInterval interval,
      BacktestConfig cfg,
      BigDecimal allocatedCapital,
      BigDecimal noTradeBand,
      List<PortfolioCandidate> candidates,
      PortfolioScoringFunction scoringFunction,
      RiskParameters riskParameters,
      Duration timeBudget,
      Consumer<String> progress,
      int checkpointEvery) {
    Objects.requireNonNull(candlesBySymbol, "candlesBySymbol");
    Objects.requireNonNull(fundingBySymbol, "fundingBySymbol");
    Objects.requireNonNull(spotCandlesBySymbol, "spotCandlesBySymbol");
    Objects.requireNonNull(interval, "interval");
    Objects.requireNonNull(cfg, "cfg");
    Objects.requireNonNull(allocatedCapital, "allocatedCapital");
    Objects.requireNonNull(noTradeBand, "noTradeBand");
    Objects.requireNonNull(candidates, "candidates");
    Objects.requireNonNull(scoringFunction, "scoringFunction");
    Objects.requireNonNull(riskParameters, "riskParameters");
    if (minTrades < 0) {
      throw new IllegalArgumentException("minTrades must be >= 0, got: " + minTrades);
    }
    // Checked here rather than left to `evaluated % checkpointEvery`: a zero would otherwise
    // surface as a bare ArithmeticException thrown from inside the candidate loop -- i.e. hours
    // into an overnight sweep, discarding it -- which is the precise failure class this task
    // exists to remove.
    if (checkpointEvery < 1) {
      throw new IllegalArgumentException("checkpointEvery must be >= 1, got: " + checkpointEvery);
    }

    List<PortfolioFoldSplitter.PortfolioFoldWindow> windows =
        PortfolioFoldSplitter.splitPurged(candlesBySymbol, folds, cfg.warmupBars(), embargoBars);

    Instant start = Instant.now();
    if (progress != null) {
      progress.accept(
          String.format(
              "[%s] portfolio calibration started candidates=%d checkpointEvery=%d timeBudget=%s",
              Instant.now(), candidates.size(), checkpointEvery, timeBudget));
    }

    List<PortfolioCalibrationResult> survivors = new ArrayList<>();
    int evaluated = 0;
    for (PortfolioCandidate candidate : candidates) {
      if (timeBudget != null && Duration.between(start, Instant.now()).compareTo(timeBudget) >= 0) {
        break;
      }
      PortfolioCalibrationResult result =
          evaluateAcrossFolds(
              candidate,
              windows,
              fundingBySymbol,
              spotCandlesBySymbol,
              interval,
              cfg,
              allocatedCapital,
              noTradeBand,
              riskParameters);
      evaluated++;
      if (result.cvTradeCountTotal() >= minTrades) {
        survivors.add(result);
      }
      if (progress != null && evaluated % checkpointEvery == 0) {
        progress.accept(progressLine(start, evaluated, survivors, scoringFunction));
      }
    }

    if (progress != null) {
      progress.accept(progressLine(start, evaluated, survivors, scoringFunction));
      progress.accept(
          String.format(
              "[%s] portfolio calibration finished evaluated=%d elapsed=%ds",
              Instant.now(), evaluated, Duration.between(start, Instant.now()).toSeconds()));
    }

    return new PortfolioCalibrationRun(rank(survivors, scoringFunction), evaluated);
  }

  /**
   * Sorts {@code results} by {@code scoringFunction} descending, ties broken by the same stable
   * params-key convention as {@link CalibrationHarness}/{@link PanelCalibrationHarness} (NFR-7).
   * Public because it is the second half of this harness's resume contract, not an internal detail:
   * a caller that merges a truncated sweep's survivors with a resumed remainder's (PLAN-022 Task A)
   * must re-rank the combined list exactly as {@link #run} itself would have, and the only way to
   * do that without duplicating the tie-break is to call this. Package-private would have confined
   * the documented resume path to this package's own tests.
   */
  public static List<PortfolioCalibrationResult> rank(
      List<PortfolioCalibrationResult> results, PortfolioScoringFunction scoringFunction) {
    List<PortfolioCalibrationResult> ranked = new ArrayList<>(results);
    ranked.sort(
        Comparator.comparingDouble(scoringFunction::score)
            .reversed()
            .thenComparing(r -> stableParamsKey(r.params())));
    return List.copyOf(ranked);
  }

  private static String progressLine(
      Instant start,
      int evaluated,
      List<PortfolioCalibrationResult> survivorsSoFar,
      PortfolioScoringFunction scoringFunction) {
    String bestStr = "n/a";
    double bestScore = Double.NEGATIVE_INFINITY;
    PortfolioCalibrationResult best = null;
    for (PortfolioCalibrationResult r : survivorsSoFar) {
      double score = scoringFunction.score(r);
      if (best == null || score > bestScore) {
        best = r;
        bestScore = score;
      }
    }
    if (best != null) {
      bestStr = String.format("%.5f %s", bestScore, best.params());
    }
    long elapsedSec = Duration.between(start, Instant.now()).toSeconds();
    return String.format(
        "[%s] evaluated=%d elapsed=%ds best=%s", Instant.now(), evaluated, elapsedSec, bestStr);
  }

  private static PortfolioCalibrationResult evaluateAcrossFolds(
      PortfolioCandidate candidate,
      List<PortfolioFoldSplitter.PortfolioFoldWindow> windows,
      Map<String, List<FundingEvent>> fundingBySymbol,
      Map<String, List<Candle>> spotCandlesBySymbol,
      CandleInterval interval,
      BacktestConfig cfg,
      BigDecimal allocatedCapital,
      BigDecimal defaultNoTradeBand,
      RiskParameters riskParameters) {

    BigDecimal noTradeBand =
        candidate.noTradeBand() != null ? candidate.noTradeBand() : defaultNoTradeBand;

    double[] sharpes = new double[windows.size()];
    BigDecimal[] returns = new BigDecimal[windows.size()];
    BigDecimal[] drawdowns = new BigDecimal[windows.size()];
    int[] tradeCounts = new int[windows.size()];
    double[] ulcerIndexes = new double[windows.size()];
    BigDecimal pooledNetPnl = BigDecimal.ZERO;
    BigDecimal pooledDeployedCapitalDays = BigDecimal.ZERO;
    // PLAN-022 Task C (N1): a symbol declared carry-capable overall (a key in spotCandlesBySymbol)
    // whose slice for THIS fold comes up empty (a data gap) must not be re-registered as
    // carry-capable for the fold with zero actual spot bars -- see the loop below. Accumulated
    // here,
    // across every fold, so it survives into the returned result instead of vanishing the moment
    // this method returns.
    Map<String, Integer> carryEligibilityGapsBySymbol = new LinkedHashMap<>();

    BacktestConfig candidateCfg = candidate.configOverride().apply(cfg);

    for (int i = 0; i < windows.size(); i++) {
      PortfolioFoldSplitter.PortfolioFoldWindow window = windows.get(i);
      Map<String, List<Candle>> scoreableChunk = window.scoreableChunkBySymbol();
      boolean anyScoreable = scoreableChunk.values().stream().anyMatch(l -> !l.isEmpty());
      if (!anyScoreable) {
        sharpes[i] = 0.0;
        returns[i] = BigDecimal.ZERO;
        drawdowns[i] = BigDecimal.ZERO;
        tradeCounts[i] = 0;
        ulcerIndexes[i] = 0.0;
        continue;
      }

      Map<String, List<Candle>> repackagedPrefix = window.repackagedPrefixBySymbol();
      Map<String, List<Candle>> combined = new LinkedHashMap<>();
      for (String symbol : window.chunkBySymbol().keySet()) {
        List<Candle> merged = new ArrayList<>(repackagedPrefix.getOrDefault(symbol, List.of()));
        merged.addAll(scoreableChunk.getOrDefault(symbol, List.of()));
        combined.put(symbol, merged);
      }

      Map<String, List<FundingEvent>> combinedFunding = new LinkedHashMap<>();
      Map<String, List<Candle>> combinedSpot = new LinkedHashMap<>();
      for (Map.Entry<String, List<Candle>> e : combined.entrySet()) {
        combinedFunding.put(
            e.getKey(),
            sliceFundingToRange(fundingBySymbol.getOrDefault(e.getKey(), List.of()), e.getValue()));
        if (spotCandlesBySymbol.containsKey(e.getKey())) {
          List<Candle> spotSlice =
              sliceCandlesToRange(spotCandlesBySymbol.get(e.getKey()), e.getValue());
          // PLAN-022 Task C (N1): an empty slice must NOT put the key in combinedSpot -- runTargets
          // derives carry-capability from key presence alone (its own Javadoc: "declared by data,
          // never inferred"), so putting an empty list here would register the symbol
          // carry-capable for this fold with zero actual spot bars, silently dropping it from every
          // bar via barsSkipped instead of leaving it single-leg-eligible or failing loud on a
          // strategy that still targets it.
          if (spotSlice.isEmpty()) {
            carryEligibilityGapsBySymbol.merge(e.getKey(), 1, Integer::sum);
          } else {
            combinedSpot.put(e.getKey(), spotSlice);
          }
        }
      }

      MutableClock clock = new MutableClock(Instant.EPOCH, ZoneOffset.UTC);
      RiskManagerPort rm = new BacktestClockSync(new RiskManager(riskParameters, clock), clock);

      BacktestResult r =
          PortfolioBacktestHarness.runTargets(
              combined,
              combinedSpot,
              combinedFunding,
              Map.of(),
              candidate.strategy(),
              interval,
              candidateCfg,
              rm,
              allocatedCapital,
              noTradeBand);

      Instant foldStart =
          scoreableChunk.values().stream()
              .filter(l -> !l.isEmpty())
              .map(l -> l.get(0).openTime())
              .min(Instant::compareTo)
              .orElseThrow();

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
    int totalTrades = sumInt(tradeCounts);
    double medianUlcer = medianDouble(ulcerIndexes.clone());
    BigDecimal pooledReturnOnDeployedCapital =
        pooledDeployedCapitalDays.signum() == 0
            ? BigDecimal.ZERO
            : pooledNetPnl
                .divide(pooledDeployedCapitalDays, IndicatorMath.MC)
                .multiply(DAYS_PER_YEAR, IndicatorMath.MC);

    // "noTradeBand" and "maxLeverage" are reserved keys that always report the value that actually
    // shaped this run, overwriting any same-named entry the caller put in its own snapshot. Echoing
    // only when absent would let the two disagree silently: a grid builder that writes the band
    // into
    // paramsSnapshot but forgets PortfolioCandidate#noTradeBand would emit rows labelled with five
    // distinct bands that every one of them ran at the harness-wide default -- exactly the silent
    // collapse PLAN-021 Task B exists to prevent. Overwriting makes that mistake self-evident
    // instead: the rows come out visibly identical.
    Map<String, Object> enriched = new LinkedHashMap<>(candidate.paramsSnapshot());
    enriched.put("noTradeBand", noTradeBand);
    enriched.put("maxLeverage", riskParameters.maxLeverage());
    Map<String, Object> paramsSnapshot = Map.copyOf(enriched);

    return new PortfolioCalibrationResult(
        paramsSnapshot,
        medianSharpe,
        medianReturn,
        worstDd,
        totalTrades,
        windows.size(),
        pooledReturnOnDeployedCapital,
        medianUlcer,
        carryEligibilityGapsBySymbol);
  }

  private static List<FundingEvent> sliceFundingToRange(
      List<FundingEvent> all, List<Candle> combined) {
    if (combined.isEmpty() || all.isEmpty()) {
      return List.of();
    }
    Instant from = combined.get(0).openTime();
    Instant to = combined.get(combined.size() - 1).openTime();
    return all.stream().filter(f -> !f.time().isBefore(from) && !f.time().isAfter(to)).toList();
  }

  private static List<Candle> sliceCandlesToRange(List<Candle> all, List<Candle> combined) {
    if (combined.isEmpty() || all.isEmpty()) {
      return List.of();
    }
    Instant from = combined.get(0).openTime();
    Instant to = combined.get(combined.size() - 1).openTime();
    return all.stream()
        .filter(c -> !c.openTime().isBefore(from) && !c.openTime().isAfter(to))
        .toList();
  }

  /**
   * Same stable tie-break convention as {@link CalibrationHarness}/{@link PanelCalibrationHarness}
   * (NFR-7).
   */
  private static String stableParamsKey(Map<String, Object> params) {
    return params.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> e.getKey() + "=" + e.getValue())
        .collect(java.util.stream.Collectors.joining(","));
  }

  private static BigDecimal foldReturn(List<EquityPoint> foldEquity) {
    if (foldEquity.isEmpty()) return BigDecimal.ZERO;
    BigDecimal first = foldEquity.get(0).equity();
    BigDecimal last = foldEquity.get(foldEquity.size() - 1).equity();
    if (first.signum() == 0) return BigDecimal.ZERO;
    return last.subtract(first, IndicatorMath.MC).divide(first, IndicatorMath.MC);
  }

  private static int sumInt(int[] xs) {
    int sum = 0;
    for (int x : xs) sum += x;
    return sum;
  }

  private static double medianDouble(double[] xs) {
    if (xs.length == 0) return 0.0;
    java.util.Arrays.sort(xs);
    int mid = xs.length / 2;
    return xs.length % 2 == 0 ? (xs[mid - 1] + xs[mid]) / 2.0 : xs[mid];
  }

  private static BigDecimal medianBig(BigDecimal[] xs) {
    if (xs.length == 0) return BigDecimal.ZERO;
    java.util.Arrays.sort(xs);
    int mid = xs.length / 2;
    return xs.length % 2 == 0
        ? xs[mid - 1].add(xs[mid], IndicatorMath.MC).divide(BigDecimal.valueOf(2), IndicatorMath.MC)
        : xs[mid];
  }

  private static BigDecimal maxBig(BigDecimal[] xs) {
    BigDecimal max = BigDecimal.ZERO;
    for (BigDecimal x : xs) {
      if (x.compareTo(max) > 0) max = x;
    }
    return max;
  }
}
