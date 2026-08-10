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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * {@code noTradeBand}) is small enough for a sequential loop to be practical, and neither
 * parallelism nor a time budget nor progress checkpointing is part of this task's stated acceptance
 * criteria. Determinism (NFR-7) holds regardless — candidates are scored in input order and ranked
 * with a stable tie-break, exactly {@link PanelCalibrationHarness#run}'s own tie-break convention.
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

    List<PortfolioFoldSplitter.PortfolioFoldWindow> windows =
        PortfolioFoldSplitter.splitPurged(candlesBySymbol, folds, cfg.warmupBars(), embargoBars);

    List<PortfolioCalibrationResult> survivors = new ArrayList<>();
    for (PortfolioCandidate candidate : candidates) {
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
      if (result.cvTradeCountTotal() >= minTrades) {
        survivors.add(result);
      }
    }

    List<PortfolioCalibrationResult> ranked = new ArrayList<>(survivors);
    ranked.sort(
        Comparator.comparingDouble(scoringFunction::score)
            .reversed()
            .thenComparing(r -> stableParamsKey(r.params())));
    return List.copyOf(ranked);
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
          combinedSpot.put(
              e.getKey(), sliceCandlesToRange(spotCandlesBySymbol.get(e.getKey()), e.getValue()));
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
        medianUlcer);
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
