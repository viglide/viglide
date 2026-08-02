package app.viglide.research.cli;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.FeeModel;
import app.viglide.core.calibrate.Candidate;
import app.viglide.core.data.CsvFundingReader;
import app.viglide.core.data.CsvKlineReader;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.params.JsonWriter;
import app.viglide.core.spi.ParameterSpaceRegistry;
import app.viglide.research.calibrate.CalibrationHarness;
import app.viglide.research.calibrate.CalibrationResult;
import app.viglide.research.calibrate.CalibrationRun;
import app.viglide.research.calibrate.FoldRunner;
import app.viglide.research.calibrate.FoldSplitter;
import app.viglide.research.calibrate.PlateauScorer;
import app.viglide.research.calibrate.ScoringFunction;
import app.viglide.research.calibrate.TrialRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Command-line entry point for parameter calibration. Launch-and-forget: parallel, walk-forward,
 * and writes machine-readable outputs under {@code --out}.
 *
 * <p>Required args:
 *
 * <ul>
 *   <li>{@code --strategy=<name>} — any name registered with {@link
 *       app.viglide.core.spi.ParameterSpaceRegistry} on the run's classpath ({@code emarsi}, {@code
 *       meanrev}, {@code macdtrend} in the public build; {@code fundingarb} too wherever the
 *       private {@code viglide-strategies} provider is also present)
 *   <li>{@code --dataset=<path>} — kline CSV
 *   <li>{@code --symbol=<sym>}, {@code --interval=<ONE_MINUTE|FIVE_MINUTES|FIFTEEN_MINUTES|
 *       ONE_HOUR|FOUR_HOURS|ONE_DAY>} (PLAN-009 Task B1)
 * </ul>
 *
 * <p>Optional args:
 *
 * <ul>
 *   <li>{@code --search=grid|random} (default {@code grid})
 *   <li>{@code --samples=10000} — only for random search
 *   <li>{@code --seed=42} — only for random search
 *   <li>{@code --folds=5}
 *   <li>{@code --parallelism=N} (default {@code processors - 1})
 *   <li>{@code --min-trades=10} — survival threshold per fold-median
 *   <li>{@code --checkpoint-every=25}
 *   <li>{@code --time-budget=PT8H} — ISO-8601 duration; loop ends cleanly when elapsed
 *   <li>{@code --top=20} — number of survivors to emit
 *   <li>{@code --out=<dir>} (default {@code build/calibrations/<timestamp>})
 * </ul>
 *
 * <p>{@code --sub-bar-dataset=<path>} (PLAN-009 Task C): an optional finer-grained kline CSV (e.g.
 * 1m bars covering the same period as {@code --dataset}) used only to resolve SL/TP ordering (OHLCV
 * strategies) or re-check the liquidation guard at sub-bar granularity (fundingarb v2) during every
 * fold's evaluation — never consumed by the strategy itself (D9-2), and never applied to the {@code
 * --funding-model=v1} path (income-only {@code FundingArbHarness} was never extended with sub-bar
 * support, matching {@code BacktestCli}). Omit for the original, decision-bar-only behaviour.
 *
 * <p>Outputs: {@code manifest.json}, {@code top.csv}, {@code top.json}, {@code progress.log}.
 */
public final class CalibrateCli {

  private CalibrateCli() {}

  public static void main(String[] argv) throws IOException {
    Map<String, String> args = Args.parse(argv);

    String strategyName = Args.require(args, "strategy");
    Path dataset = Paths.get(Args.require(args, "dataset"));
    String symbol = Args.require(args, "symbol");
    CandleInterval interval = CandleInterval.valueOf(Args.require(args, "interval"));

    String searchMode = Args.opt(args, "search", "grid");
    long seed = Args.longOpt(args, "seed", 42L);
    int samples = Args.intOpt(args, "samples", 10_000);
    int folds = Args.intOpt(args, "folds", 5);
    int parallelism = Args.intOpt(args, "parallelism", -1);
    // PLAN-013 Task D: default raised 10 -> 30 to match ADR-0016's K1' condition 2. Explicit
    // --min-trades still overrides, e.g. to reproduce a pre-PLAN-013 run at its original value.
    int minTrades = Args.intOpt(args, "min-trades", 30);
    int checkpointEvery = Args.intOpt(args, "checkpoint-every", 25);
    int topK = Args.intOpt(args, "top", 20);
    // PLAN-013 Task D (finding F4): carry-yield is the ADR-0016-recommended objective; the old
    // median-cv-sharpe default stays reachable (--objective=median-cv-sharpe) so a historical run
    // can still be reproduced byte-identically.
    ScoringFunction scoringFunction =
        ScoringFunction.byName(Args.opt(args, "objective", "carry-yield"));
    boolean legacyMedianTradeCountFilter = Args.flag(args, "legacy-median-trade-filter");
    // PLAN-013 Task F (finding F7): absent by default -- an existing run's own reproducibility
    // (byte-identical ranking for the same dataset/seed) must not change just because this flag
    // exists. Pass e.g. --embargo-bars=6 (FundingArbParameterSpace.defaults().maxLookback()) to
    // opt in; see docs/runbook.md §3 for how to choose a value for a given strategy.
    Integer embargoBars =
        Args.opt(args, "embargo-bars", null) == null ? null : Args.intOpt(args, "embargo-bars", 0);
    Duration timeBudget = parseDuration(args.get("time-budget"));

    Path outDir = Paths.get(Args.opt(args, "out", defaultOutDir()));
    Files.createDirectories(outDir);
    Path progressLog = outDir.resolve("progress.log");

    BacktestConfig baseCfg = BacktestConfig.hourlyDefaults();
    BacktestConfig cfg =
        new BacktestConfig(
            baseCfg.startingCash(),
            FeeModel.binanceDefault(),
            Args.intOpt(args, "warmup-bars", baseCfg.warmupBars()),
            baseCfg.maxPositionPct(),
            baseCfg.stopLossPct(),
            baseCfg.takeProfitPct(),
            Args.intOpt(args, "bars-per-year", BacktestConfig.barsPerYearFor(interval)));

    List<Candle> candles;
    try (var stream = CsvKlineReader.stream(dataset)) {
      candles = stream.toList();
    }

    // PLAN-009 Task C: optional finer-grained series for sub-bar SL/TP (OHLCV) or liquidation-guard
    // (fundingarb v2) resolution during fold evaluation. Empty when not supplied, preserving exact
    // prior behaviour.
    String subBarDataset = args.get("sub-bar-dataset");
    List<Candle> subBarCandles = List.of();
    if (subBarDataset != null && !subBarDataset.isBlank()) {
      try (var s = CsvKlineReader.stream(Paths.get(subBarDataset))) {
        subBarCandles = s.toList();
      }
    }

    // Funding-aware path: load events and pick the funding fold runner. PLAN-008 Task F:
    // --funding-model=v1|v2 (default v2) selects between FundingArbHarness (income-only) and
    // FundingArbHarnessV2 (two-leg, needs --spot-dataset too).
    String fundingDataset = args.get("funding-dataset");
    String fundingModel = Args.opt(args, "funding-model", "v2");
    if (!"v1".equalsIgnoreCase(fundingModel) && !"v2".equalsIgnoreCase(fundingModel)) {
      throw new IllegalArgumentException(
          "unknown --funding-model='" + fundingModel + "'; expected v1|v2");
    }
    FoldRunner runner =
        subBarCandles.isEmpty()
            ? FoldRunner.defaultOhlcv()
            : FoldRunner.defaultOhlcv(subBarCandles);
    int fundingCount = 0;
    if ("fundingarb".equals(strategyName)
        || (fundingDataset != null && !fundingDataset.isBlank())) {
      if (fundingDataset == null || fundingDataset.isBlank()) {
        throw new IllegalArgumentException(
            "--funding-dataset is required when --strategy=fundingarb");
      }
      List<FundingEvent> events;
      try (var s = CsvFundingReader.stream(Paths.get(fundingDataset))) {
        events = s.toList();
      }
      fundingCount = events.size();
      if ("v2".equalsIgnoreCase(fundingModel)) {
        List<Candle> spotCandles;
        try (var s = CsvKlineReader.stream(Paths.get(Args.require(args, "spot-dataset")))) {
          spotCandles = s.toList();
        }
        runner =
            subBarCandles.isEmpty()
                ? FoldRunner.withFundingV2(events, spotCandles)
                : FoldRunner.withFundingV2(events, spotCandles, subBarCandles);
      } else {
        runner = FoldRunner.withFunding(events);
      }
    }

    // Written now (trials=0 placeholder) so a run killed mid-flight — plausible: Task G's rolling
    // matrix runs unattended for hours — still leaves behind which dataset/args were attempted;
    // overwritten below with the real trial count once the run actually finishes.
    writeManifest(
        outDir, args, candles.size(), fundingCount, seed, searchMode, strategyName, 0, 0.0);

    Stream<Candidate> candidates =
        candidatesFor(strategyName, searchMode, seed, samples, cfg.fees());

    try (var writer =
        Files.newBufferedWriter(
            progressLog,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND)) {
      java.util.function.Consumer<String> progress =
          line -> {
            System.out.println(line);
            try {
              writer.write(line);
              writer.newLine();
              writer.flush();
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          };
      CalibrationRun run;
      if (embargoBars == null) {
        run =
            CalibrationHarness.run(
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
                runner,
                scoringFunction,
                legacyMedianTradeCountFilter);
      } else {
        // PLAN-013 Task F: purged K-fold with embargo -- fold boundaries purge the prefix
        // contamination splitWithPrefix's own sharing of candles between adjacent folds implies,
        // plus this many extra candles as a safety margin against residual serial correlation.
        CalibrationHarness.PurgedCalibrationRun purgedRun =
            CalibrationHarness.runPurged(
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
                runner,
                scoringFunction,
                legacyMedianTradeCountFilter,
                embargoBars);
        run = purgedRun.run();
        FoldSplitter.PurgeReport pr = purgedRun.purgeReport();
        System.out.println(
            String.format(
                "purge report: %d of %d chunk bars purged (%.1f%%) -- a run that purges most of"
                    + " its data is telling you the fold count is too high for the lookback",
                pr.totalPurgedBars(), pr.totalChunkBars(), pr.purgeFraction() * 100));
      }

      // PLAN-013 Task H (review finding F4): DSR must be deflated by the TRUE cumulative search
      // effort against this dataset, not just this run's own candidate count -- CalibrationHarness
      // only ever knows about itself. Append this run to the registry first, then read back the
      // cumulative total (including what was just appended) so "trials" in the manifest is always
      // honest as of the moment this run finished, regardless of how many prior runs came before.
      Path trialRegistryPath = Paths.get(Args.opt(args, "trial-registry", "research-trials.jsonl"));
      String datasetFingerprint =
          TrialRegistry.datasetFingerprint(symbol, interval.name(), dataset);
      TrialRegistry.append(
          trialRegistryPath,
          new TrialRegistry.TrialRecord(
              Instant.now(),
              strategyName,
              datasetFingerprint,
              run.trials(),
              seed,
              scoringFunction == ScoringFunction.CARRY_YIELD ? "carry-yield" : "median-cv-sharpe"));
      int cumulativeTrials =
          TrialRegistry.cumulativeTrialsFor(trialRegistryPath, datasetFingerprint);
      System.out.println(
          String.format(
              "trial registry: this run=%d candidates, cumulative for this dataset=%d (%s)",
              run.trials(), cumulativeTrials, trialRegistryPath));

      // PLAN-008 D.1: overwrite with the real evaluated count now that the run has finished —
      // --time-budget truncation means this can be less than the full grid/sample size, so
      // "trials" only ever reflects what actually ran, never what was requested. PLAN-013 Task H:
      // the manifest's own "trials" field is now the CUMULATIVE count (feeding PromoteCli's DSR
      // calculation unchanged, since it just reads whatever this field says) -- "trials" has meant
      // "this run's own count" since PLAN-008; this is a deliberate, documented redefinition, not a
      // silent one (see docs/runbook.md §3's trial-registry section for why a rising trial count is
      // *supposed* to make results look worse over successive runs, not a regression).
      writeManifest(
          outDir,
          args,
          candles.size(),
          fundingCount,
          seed,
          searchMode,
          strategyName,
          cumulativeTrials,
          run.trialSharpeVariancePerPeriod());

      List<CalibrationResult> ranked = run.survivors();
      // PLAN-009 Task E: plateau score for the top-20 (or fewer) survivors — same fold windows
      // (with warm-up prefixes, Task D) the main ranking itself was evaluated against, so a
      // neighbor's score is directly comparable to the candidate it perturbs.
      List<FoldSplitter.FoldWindow> foldWindows =
          candles.size() >= folds
              ? FoldSplitter.splitWithPrefix(candles, folds, cfg.warmupBars())
              : List.of();
      List<Double> plateauScores = new ArrayList<>(ranked.size());
      for (int i = 0; i < ranked.size(); i++) {
        if (i < 20 && !foldWindows.isEmpty()) {
          plateauScores.add(
              PlateauScorer.plateauScore(
                  strategyName, ranked.get(i), foldWindows, symbol, interval, cfg, runner));
        } else {
          plateauScores.add(null);
        }
      }
      writeTop(outDir, ranked, plateauScores, topK, scoringFunction);
      System.out.println(
          String.format(
              "calibration: trials=%d survivors=%d top1_sharpe=%s → %s",
              run.trials(),
              ranked.size(),
              ranked.isEmpty() ? "n/a" : String.format("%.3f", ranked.get(0).cvSharpeMedian()),
              outDir));
    }
  }

  // ── candidate dispatch ───────────────────────────────────────────────────────────────────────

  private static Stream<Candidate> candidatesFor(
      String strategy, String searchMode, long seed, int samples, FeeModel feeModel) {
    // PLAN-018 R-2.2: resolved via ParameterSpaceRegistry (ServiceLoader), never by importing a
    // concrete parameter-space class directly — this CLI is public (viglide-research) and must
    // stay buildable/runnable with only the public providers on the classpath (emarsi, meanrev,
    // macdtrend); a private provider such as fundingarb's registers itself the same way and is
    // simply absent from that set when viglide-strategies isn't present.
    var provider =
        ParameterSpaceRegistry.load()
            .find(strategy)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "unknown --strategy='"
                            + strategy
                            + "'; known: "
                            + ParameterSpaceRegistry.load().names()));
    // PLAN-013 Task E: feeModel is the calibration run's own cfg.fees(), so a strategy whose
    // confidence derivation depends on cost (e.g. fundingarb) matches whatever the harness
    // actually charges it during evaluation; spaces that don't need it simply ignore the arg.
    return "random".equals(searchMode)
        ? provider.random(seed, samples, feeModel)
        : provider.grid(feeModel);
  }

  // ── output writers ───────────────────────────────────────────────────────────────────────────

  private static void writeManifest(
      Path outDir,
      Map<String, String> args,
      int candleCount,
      int fundingCount,
      long seed,
      String searchMode,
      String strategyName,
      int trials,
      double trialSharpeVariancePerPeriod)
      throws IOException {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("startedAt", Instant.now().toString());
    m.put("strategy", strategyName);
    m.put("candleCount", candleCount);
    m.put("fundingCount", fundingCount);
    m.put("seed", seed);
    m.put("searchMode", searchMode);
    m.put("javaVersion", Runtime.version().toString());
    // PLAN-008 D.1: candidates actually evaluated (authoritative under --time-budget truncation)
    // and the population variance of their per-period Sharpe, feeding PromoteCli's DSR calc.
    m.put("trials", trials);
    m.put("trialSharpeVariancePerPeriod", trialSharpeVariancePerPeriod);
    // PLAN-009 Task C: every calibration run records whether sub-bar execution realism was on
    // during fold evaluation, so a matrix note never has to guess which mode produced a given cell.
    m.put("subBarResolution", args.get("sub-bar-dataset") != null);
    // PLAN-013 Task D: the RESOLVED objective, not just whatever (if anything) was literally typed
    // on the command line -- --objective defaults to carry-yield now, so a run relying on that
    // default must still be traceable without re-deriving it from CalibrateCli's own source.
    m.put("objective", Args.opt(args, "objective", "carry-yield"));
    m.put("legacyMedianTradeCountFilter", Args.flag(args, "legacy-median-trade-filter"));
    Map<String, Object> argMap = new LinkedHashMap<>(args);
    m.put("args", argMap);
    Files.writeString(outDir.resolve("manifest.json"), JsonWriter.pretty(m));
  }

  private static void writeTop(
      Path outDir,
      List<CalibrationResult> ranked,
      List<Double> plateauScores,
      int topK,
      ScoringFunction scoringFunction)
      throws IOException {
    List<CalibrationResult> top = ranked.subList(0, Math.min(topK, ranked.size()));
    List<Double> topPlateau = plateauScores.subList(0, Math.min(topK, plateauScores.size()));
    Files.writeString(outDir.resolve("top.csv"), topCsv(top, topPlateau, scoringFunction));
    Files.writeString(
        outDir.resolve("top.json"), JsonWriter.pretty(topJson(top, topPlateau, scoringFunction)));
  }

  /** Generic CSV — header is "{strategy params...},metrics" derived from the first row's keys. */
  private static String topCsv(
      List<CalibrationResult> top, List<Double> plateauScores, ScoringFunction scoringFunction) {
    StringBuilder sb = new StringBuilder();
    String metricsHeader =
        "cv_sharpe_median,cv_return_median,cv_drawdown_worst,cv_trade_count_median,"
            + "cv_trade_count_total,cv_return_on_deployed_capital_pooled,cv_ulcer_index_median,"
            + "objective_score,folds,plateau_score\n";
    if (top.isEmpty()) {
      sb.append(metricsHeader);
      return sb.toString();
    }
    Set<String> paramKeys = new LinkedHashSet<>(top.get(0).params().keySet());
    for (String k : paramKeys) sb.append(camelToSnake(k)).append(',');
    sb.append(metricsHeader);
    for (int i = 0; i < top.size(); i++) {
      CalibrationResult r = top.get(i);
      for (String k : paramKeys) {
        Object v = r.params().get(k);
        sb.append(v == null ? "" : v.toString()).append(',');
      }
      Double plateau = plateauScores.get(i);
      sb.append(r.cvSharpeMedian())
          .append(',')
          .append(r.cvTotalReturnMedian().toPlainString())
          .append(',')
          .append(r.cvMaxDrawdownWorst().toPlainString())
          .append(',')
          .append(r.cvTradeCountMedian())
          .append(',')
          .append(r.cvTradeCountTotal())
          .append(',')
          .append(r.cvReturnOnDeployedCapitalPooled().toPlainString())
          .append(',')
          .append(r.cvUlcerIndexMedian())
          .append(',')
          .append(scoringFunction.score(r))
          .append(',')
          .append(r.foldsEvaluated())
          .append(',')
          .append(plateau == null ? "" : plateau)
          .append('\n');
    }
    return sb.toString();
  }

  /**
   * PLAN-008 D.2: writes {@code cv*} keys — {@link Args#jsonFallback} is how readers stay
   * compatible with {@code oos*}-keyed entries persisted before this rename. PLAN-009 Task E:
   * {@code plateauScore} is present (non-null) only for the top 20 — the perturbation neighborhood
   * is bounded to that many candidates by design (effort/runtime trade-off).
   */
  private static List<Map<String, Object>> topJson(
      List<CalibrationResult> top, List<Double> plateauScores, ScoringFunction scoringFunction) {
    List<Map<String, Object>> out = new ArrayList<>(top.size());
    for (int i = 0; i < top.size(); i++) {
      CalibrationResult r = top.get(i);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("params", r.params());
      m.put("cvSharpeMedian", r.cvSharpeMedian());
      m.put("cvTotalReturnMedian", r.cvTotalReturnMedian());
      m.put("cvMaxDrawdownWorst", r.cvMaxDrawdownWorst());
      m.put("cvTradeCountMedian", r.cvTradeCountMedian());
      // PLAN-013 Task D (finding F4): the actual ranking objective plus the metrics it draws on.
      m.put("cvTradeCountTotal", r.cvTradeCountTotal());
      m.put("cvReturnOnDeployedCapitalPooled", r.cvReturnOnDeployedCapitalPooled());
      m.put("cvUlcerIndexMedian", r.cvUlcerIndexMedian());
      m.put("objectiveScore", scoringFunction.score(r));
      m.put("foldsEvaluated", r.foldsEvaluated());
      m.put("plateauScore", plateauScores.get(i));
      out.add(m);
    }
    return out;
  }

  private static String camelToSnake(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 4);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isUpperCase(c)) {
        if (i > 0) sb.append('_');
        sb.append(Character.toLowerCase(c));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static Duration parseDuration(String s) {
    if (s == null || s.isBlank()) return null;
    return Duration.parse(s);
  }

  private static String defaultOutDir() {
    String ts =
        LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    return "build/calibrations/" + ts;
  }
}
