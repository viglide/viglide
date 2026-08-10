package app.viglide.research.cli;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.FeeModel;
import app.viglide.core.calibrate.PortfolioCandidate;
import app.viglide.core.data.CsvFundingReader;
import app.viglide.core.data.CsvKlineReader;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.params.JsonWriter;
import app.viglide.core.risk.RiskParameters;
import app.viglide.core.spi.PortfolioParameterSpaceRegistry;
import app.viglide.research.calibrate.PortfolioCalibrationHarness;
import app.viglide.research.calibrate.PortfolioCalibrationResult;
import app.viglide.research.calibrate.PortfolioCalibrationRun;
import app.viglide.research.calibrate.PortfolioScoringFunction;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
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
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Command-line entry point for a {@link app.viglide.core.spi.PortfolioStrategy}'s panel calibration
 * fit (PLAN-022 Task B) — {@link PortfolioCalibrationHarness}'s equivalent of {@link CalibrateCli},
 * so PLAN-016 Task C's carry half is launched by a quotable command instead of a JUnit test class
 * (the plan's own complaint about {@code Plan015TaskESpike} / {@code Plan019TaskALiquidationTriage}
 * / {@code Plan019TaskCRegressionAnchor}). Reuses {@link PortfolioCli}'s dataset-loading vocabulary
 * ({@code --pairs}, {@code --label}, {@code --datasets-dir}, {@code --starting-cash}, {@code
 * --fee-mode}, {@code --out}, {@code --rm-*}) and its exact interval-tag/fee-model/risk-parameter
 * code rather than a second dialect for the same concepts.
 *
 * <p>Required args:
 *
 * <ul>
 *   <li>{@code --pairs=BTCUSDT,ETHUSDT,…}
 *   <li>{@code --strategy=<name>} — resolved via {@link PortfolioParameterSpaceRegistry} (a private
 *       provider such as {@code carryranking} registers itself the same way {@code fundingarb}'s
 *       {@link app.viglide.core.spi.ParameterSpaceProvider} does; this CLI never imports it
 *       directly, same rule {@link CalibrateCli} follows for the single-symbol case)
 *   <li>{@code --label=2024} — dataset year; resolves each pair's kline, funding, and spot datasets
 *       with the identical {@code {PAIR}_{intervalTag}_{label}.csv} / {@code
 *       {PAIR}_funding_{label}.csv} / {@code {PAIR}_spot_{intervalTag}_{label}.csv} convention
 *       {@link PortfolioCli}'s {@code --funding-model=v2} path uses
 * </ul>
 *
 * <p>Optional args:
 *
 * <ul>
 *   <li>{@code --interval=ONE_HOUR}, {@code --datasets-dir=data}
 *   <li>{@code --starting-cash=10000}, {@code --fee-mode=taker|maker}, {@code --fee-scale=1}
 *   <li>{@code --rm-max-leverage=2.0} (PLAN-021 Task C — pass {@code 1.0} to reproduce PLAN-019
 *       Task A's SURVIVABLE freeze for {@code CarryRankingStrategy}), plus every other {@code
 *       --rm-*} flag {@link PortfolioCli} accepts
 *   <li>{@code --no-trade-band=0.05} (the harness-wide default — PLAN-021 Task B; a candidate's own
 *       {@link PortfolioCandidate#noTradeBand()} still overrides it per-candidate)
 *   <li>{@code --folds=5}, {@code --embargo-bars=0}, {@code --min-trades=30} (ADR-0016's K1' floor)
 *   <li>{@code --search=grid|random}, {@code --samples=10000}, {@code --seed=42}
 *   <li>{@code --objective=carry-yield} (default) or {@code median-cv-sharpe}
 *   <li>{@code --time-budget=PT8H}, {@code --checkpoint-every=25} (PLAN-022 Task A)
 *   <li>{@code --top=20}, {@code --out=build/calibrations/<timestamp>}
 * </ul>
 *
 * <p>A pair missing its kline, funding, or spot dataset for {@code --label} is skipped with a
 * warning — never silently — same convention as {@link PortfolioCli}; included/skipped pairs are
 * recorded in {@code manifest.json} so a partial-coverage run is never mistaken for a full one.
 *
 * <p><strong>Never writes {@code winners.json}</strong> (PLAN-016 Task C's own trap, inherited here
 * unchanged): this CLI fits fresh candidates from the strategy's own parameter space, never reads a
 * prior promotion either — promotion is a decision that follows the K1' verdict, never a side
 * effect of a sweep.
 *
 * <p>Outputs, under {@code --out}: {@code manifest.json}, {@code top.csv}, {@code top.json}, {@code
 * progress.log}.
 */
public final class PortfolioCalibrateCli {

  private PortfolioCalibrateCli() {}

  public static void main(String[] argv) throws IOException {
    System.exit(run(argv));
  }

  // Public (not package-private): a future test in a private strategy module (PLAN-018 R-5
  // pattern) can call this directly instead of going through main()'s System.exit, same reason
  // PortfolioCli#run is public.
  public static int run(String[] argv) throws IOException {
    Map<String, String> args = Args.parse(argv);

    List<String> pairs = List.of(Args.require(args, "pairs").split(","));
    String strategyName = Args.require(args, "strategy");
    String label = Args.require(args, "label");
    Path datasetsDir = Paths.get(Args.opt(args, "datasets-dir", "data"));
    CandleInterval interval = CandleInterval.valueOf(Args.opt(args, "interval", "ONE_HOUR"));
    String intervalTag = PortfolioCli.INTERVAL_TAG.get(interval);

    int folds = Args.intOpt(args, "folds", 5);
    int embargoBars = Args.intOpt(args, "embargo-bars", 0);
    // PLAN-013 Task D: matches CalibrateCli's own default -- ADR-0016's K1' condition 2 floor.
    int minTrades = Args.intOpt(args, "min-trades", 30);
    long seed = Args.longOpt(args, "seed", 42L);
    int samples = Args.intOpt(args, "samples", 10_000);
    String searchMode = Args.opt(args, "search", "grid");
    int checkpointEvery = Args.intOpt(args, "checkpoint-every", 25);
    int topK = Args.intOpt(args, "top", 20);
    Duration timeBudget = parseDuration(args.get("time-budget"));
    PortfolioScoringFunction scoringFunction =
        PortfolioScoringFunction.byName(Args.opt(args, "objective", "carry-yield"));
    BigDecimal noTradeBand = Args.bigDecOpt(args, "no-trade-band", new BigDecimal("0.05"));
    BigDecimal allocatedCapital = Args.bigDecOpt(args, "starting-cash", new BigDecimal("10000"));
    RiskParameters riskParameters = PortfolioCli.riskParametersFromArgs(args);
    FeeModel fees = PortfolioCli.buildFees(args);

    BacktestConfig cfg =
        new BacktestConfig(
            allocatedCapital,
            fees,
            Args.intOpt(args, "warmup-bars", 200),
            BigDecimal.ONE, // maxPositionPct: unused here (RM sizes instead), same as PortfolioCli
            null,
            null,
            Args.intOpt(args, "bars-per-year", BacktestConfig.barsPerYearFor(interval)));

    Map<String, List<Candle>> candlesBySymbol = new LinkedHashMap<>();
    Map<String, List<FundingEvent>> fundingBySymbol = new LinkedHashMap<>();
    Map<String, List<Candle>> spotBySymbol = new LinkedHashMap<>();
    List<String> included = new ArrayList<>();
    List<String> skipped = new ArrayList<>();

    for (String pair : pairs) {
      Path klinePath = datasetsDir.resolve(pair + "_" + intervalTag + "_" + label + ".csv");
      Path fundingPath = datasetsDir.resolve(pair + "_funding_" + label + ".csv");
      Path spotPath = datasetsDir.resolve(pair + "_spot_" + intervalTag + "_" + label + ".csv");
      if (!Files.exists(klinePath) || !Files.exists(fundingPath) || !Files.exists(spotPath)) {
        System.out.println(
            "[SKIP] missing dataset(s) for "
                + pair
                + " (need "
                + klinePath
                + ", "
                + fundingPath
                + ", "
                + spotPath
                + ") — excluding");
        skipped.add(pair);
        continue;
      }
      List<Candle> candles;
      try (var s = CsvKlineReader.stream(klinePath)) {
        candles = s.toList();
      }
      List<FundingEvent> funding;
      try (var s = CsvFundingReader.stream(fundingPath)) {
        funding = s.toList();
      }
      List<Candle> spot;
      try (var s = CsvKlineReader.stream(spotPath)) {
        spot = s.toList();
      }
      candlesBySymbol.put(pair, candles);
      fundingBySymbol.put(pair, funding);
      spotBySymbol.put(pair, spot);
      included.add(pair);
    }

    if (included.isEmpty()) {
      System.err.println(
          "REFUSED: no pair had kline+funding+spot datasets for label='"
              + label
              + "' — nothing to calibrate");
      return 1;
    }

    var provider =
        PortfolioParameterSpaceRegistry.load()
            .find(strategyName)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "unknown --strategy='"
                            + strategyName
                            + "'; known: "
                            + PortfolioParameterSpaceRegistry.load().names()));
    Stream<PortfolioCandidate> candidateStream =
        "random".equals(searchMode) ? provider.random(seed, samples, fees) : provider.grid(fees);
    List<PortfolioCandidate> candidates = candidateStream.toList();

    Path outDir = Paths.get(Args.opt(args, "out", defaultOutDir()));
    Files.createDirectories(outDir);
    Path progressLog = outDir.resolve("progress.log");

    // Written now (trials=0 placeholder), same convention as CalibrateCli -- a run killed
    // mid-flight still leaves behind which dataset/args were attempted, overwritten below once the
    // run actually finishes. The placeholder deliberately reports trials=0 against the real
    // candidatesRequested: that pair is what makes an aborted run self-evidently incomplete under
    // the "trials < candidatesRequested" rule below, so getting the two the wrong way round would
    // leave an abort looking like a finished sweep -- the exact silence PLAN-022 Task A exists to
    // remove.
    writeManifest(
        outDir, args, strategyName, included, skipped, 0, seed, searchMode, candidates.size());

    try (var writer =
        Files.newBufferedWriter(
            progressLog, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
      Consumer<String> progress =
          line -> {
            System.out.println(line);
            try {
              writer.write(line);
              writer.newLine();
              writer.flush();
            } catch (IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          };

      PortfolioCalibrationRun run =
          PortfolioCalibrationHarness.run(
              candlesBySymbol,
              fundingBySymbol,
              spotBySymbol,
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

      writeManifest(
          outDir,
          args,
          strategyName,
          included,
          skipped,
          run.trials(),
          seed,
          searchMode,
          candidates.size());
      writeTop(outDir, run.survivors(), topK, scoringFunction);
      System.out.println(
          String.format(
              "portfolio calibration: candidates=%d trials=%d survivors=%d top1_score=%s → %s",
              candidates.size(),
              run.trials(),
              run.survivors().size(),
              run.survivors().isEmpty()
                  ? "n/a"
                  : String.format("%.5f", scoringFunction.score(run.survivors().get(0))),
              outDir));
    }
    return 0;
  }

  private static void writeManifest(
      Path outDir,
      Map<String, String> args,
      String strategyName,
      List<String> included,
      List<String> skipped,
      int trials,
      long seed,
      String searchMode,
      int candidatesRequested)
      throws IOException {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("startedAt", Instant.now().toString());
    m.put("strategy", strategyName);
    m.put("pairsIncluded", included);
    m.put("pairsSkipped", skipped);
    m.put("seed", seed);
    m.put("searchMode", searchMode);
    m.put("javaVersion", Runtime.version().toString());
    // Truncation (--time-budget) is visible here the same way CalibrateCli's own "trials" field
    // makes it visible: trials < candidatesRequested means the sweep was cut short, never silently
    // reported as a completed one (PLAN-022 Task A).
    m.put("candidatesRequested", candidatesRequested);
    m.put("trials", trials);
    m.put("objective", Args.opt(args, "objective", "carry-yield"));
    Map<String, Object> argMap = new LinkedHashMap<>(args);
    m.put("args", argMap);
    Files.writeString(outDir.resolve("manifest.json"), JsonWriter.pretty(m));
  }

  private static void writeTop(
      Path outDir, List<PortfolioCalibrationResult> ranked, int topK, PortfolioScoringFunction sf)
      throws IOException {
    List<PortfolioCalibrationResult> top = ranked.subList(0, Math.min(topK, ranked.size()));
    Files.writeString(outDir.resolve("top.csv"), topCsv(top, sf));
    Files.writeString(outDir.resolve("top.json"), JsonWriter.pretty(topJson(top, sf)));
  }

  private static String topCsv(List<PortfolioCalibrationResult> top, PortfolioScoringFunction sf) {
    StringBuilder sb = new StringBuilder();
    String metricsHeader =
        "cv_sharpe_median,cv_total_return_median,cv_max_drawdown_worst,cv_trade_count_total,"
            + "cv_return_on_deployed_capital_pooled,cv_ulcer_index_median,objective_score,"
            + "folds_evaluated,carry_eligibility_gaps\n";
    if (top.isEmpty()) {
      sb.append(metricsHeader);
      return sb.toString();
    }
    Set<String> paramKeys = new LinkedHashSet<>(top.get(0).params().keySet());
    for (String k : paramKeys) sb.append(camelToSnake(k)).append(',');
    sb.append(metricsHeader);
    for (PortfolioCalibrationResult r : top) {
      for (String k : paramKeys) {
        Object v = r.params().get(k);
        sb.append(v == null ? "" : v.toString()).append(',');
      }
      sb.append(r.cvSharpeMedian())
          .append(',')
          .append(r.cvTotalReturnMedian().toPlainString())
          .append(',')
          .append(r.cvMaxDrawdownWorst().toPlainString())
          .append(',')
          .append(r.cvTradeCountTotal())
          .append(',')
          .append(r.cvReturnOnDeployedCapitalPooled().toPlainString())
          .append(',')
          .append(r.cvUlcerIndexMedian())
          .append(',')
          .append(sf.score(r))
          .append(',')
          .append(r.foldsEvaluated())
          .append(',')
          .append(
              r.carryEligibilityGapsBySymbol().isEmpty() ? "" : r.carryEligibilityGapsBySymbol())
          .append('\n');
    }
    return sb.toString();
  }

  private static List<Map<String, Object>> topJson(
      List<PortfolioCalibrationResult> top, PortfolioScoringFunction sf) {
    List<Map<String, Object>> out = new ArrayList<>(top.size());
    for (PortfolioCalibrationResult r : top) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("params", r.params());
      m.put("cvSharpeMedian", r.cvSharpeMedian());
      m.put("cvTotalReturnMedian", r.cvTotalReturnMedian());
      m.put("cvMaxDrawdownWorst", r.cvMaxDrawdownWorst());
      m.put("cvTradeCountTotal", r.cvTradeCountTotal());
      m.put("cvReturnOnDeployedCapitalPooled", r.cvReturnOnDeployedCapitalPooled());
      m.put("cvUlcerIndexMedian", r.cvUlcerIndexMedian());
      m.put("objectiveScore", sf.score(r));
      m.put("foldsEvaluated", r.foldsEvaluated());
      // PLAN-022 Task C (N1): empty map is the common case -- a non-empty one flags a real-corpus
      // data gap that shrank this candidate's cross-section for one or more folds.
      m.put("carryEligibilityGapsBySymbol", r.carryEligibilityGapsBySymbol());
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
    return "build/calibrations/portfolio_" + ts;
  }
}
