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
import app.viglide.research.calibrate.PanelCalibrationHarness;
import app.viglide.research.calibrate.TrialRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
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

/**
 * Command-line entry point for {@link PanelCalibrationHarness} (PLAN-023 Task A).
 *
 * <p><strong>Until this existed, the panel fit could not be run at all.</strong> {@code
 * PanelCalibrationHarness} was built by PLAN-013 Task G — which called it "the highest-value item
 * in the plan", the thing that replaces ~60 independent per-pair-per-year fits (each validated on
 * 1–2 trades, review finding F4) with one fit across the whole panel — and then never acquired a
 * caller. Its only references in either repository were its own test and two Javadoc mentions, so a
 * grep for the symbol made it look wired in when nothing could invoke it. PLAN-016's
 * pre-registration names the panel fit as a required fit mode for {@code fundingarb}; this is what
 * makes that requirement satisfiable.
 *
 * <p>Deliberately the same argument vocabulary as {@link CalibrateCli} and {@link
 * PortfolioCalibrateCli} rather than a third dialect for the same concepts.
 *
 * <p>Required: {@code --pairs}, {@code --strategy}, {@code --label}. Optional: {@code --interval},
 * {@code --datasets-dir}, {@code --starting-cash}, {@code --fee-mode}, {@code --fee-scale}, {@code
 * --warmup-bars}, {@code --search}, {@code --samples}, {@code --seed}, {@code --parallelism},
 * {@code --time-budget}, {@code --checkpoint-every}, {@code --top}, {@code --trial-registry},
 * {@code --out}.
 *
 * <p>The strategy is resolved through {@link ParameterSpaceRegistry}, so this public CLI never
 * imports a private strategy's package; each {@link Candidate} already carries its own built
 * strategy, which is what the harness's {@code candidateBuilder} returns.
 *
 * <p><strong>Never writes {@code winners.json}.</strong> Promotion follows the K1′ verdict, never a
 * sweep — the same trap PLAN-016 Task C names and {@link PortfolioCalibrateCli} already inherits.
 */
public final class PanelCalibrateCli {

  private PanelCalibrateCli() {}

  public static void main(String[] argv) throws IOException {
    System.exit(run(argv));
  }

  public static int run(String[] argv) throws IOException {
    Map<String, String> args = Args.parse(argv);

    List<String> pairs = List.of(Args.require(args, "pairs").split(","));
    String strategyName = Args.require(args, "strategy");
    String label = Args.require(args, "label");
    Path datasetsDir = Paths.get(Args.opt(args, "datasets-dir", "data"));
    CandleInterval interval = CandleInterval.valueOf(Args.opt(args, "interval", "ONE_HOUR"));
    String intervalTag = PortfolioCli.INTERVAL_TAG.get(interval);

    long seed = Args.longOpt(args, "seed", 42L);
    int samples = Args.intOpt(args, "samples", 300);
    String searchMode = Args.opt(args, "search", "random");
    int parallelism = Args.intOpt(args, "parallelism", 0); // 0 => harness picks cores-1
    int checkpointEvery = Args.intOpt(args, "checkpoint-every", 25);
    int topK = Args.intOpt(args, "top", 20);
    Duration timeBudget = parseDuration(args.get("time-budget"));
    BigDecimal startingCash = Args.bigDecOpt(args, "starting-cash", new BigDecimal("10000"));
    FeeModel fees = PortfolioCli.buildFees(args);

    BacktestConfig cfg =
        new BacktestConfig(
            startingCash,
            fees,
            Args.intOpt(args, "warmup-bars", 200),
            BigDecimal.ONE,
            null,
            null,
            Args.intOpt(args, "bars-per-year", BacktestConfig.barsPerYearFor(interval)));

    Map<String, List<Candle>> candlesBySymbol = new LinkedHashMap<>();
    Map<String, List<FundingEvent>> fundingBySymbol = new LinkedHashMap<>();
    Map<String, List<Path>> datasetsBySymbol = new LinkedHashMap<>();
    List<String> included = new ArrayList<>();
    List<String> skipped = new ArrayList<>();

    for (String pair : pairs) {
      Path klinePath = datasetsDir.resolve(pair + "_" + intervalTag + "_" + label + ".csv");
      Path fundingPath = datasetsDir.resolve(pair + "_funding_" + label + ".csv");
      if (!Files.exists(klinePath) || !Files.exists(fundingPath)) {
        System.out.println(
            "[SKIP] missing dataset(s) for "
                + pair
                + " (need "
                + klinePath
                + ", "
                + fundingPath
                + ") — excluding");
        skipped.add(pair);
        continue;
      }
      try (var s = CsvKlineReader.stream(klinePath)) {
        candlesBySymbol.put(pair, s.toList());
      }
      try (var s = CsvFundingReader.stream(fundingPath)) {
        fundingBySymbol.put(pair, s.toList());
      }
      datasetsBySymbol.put(pair, List.of(klinePath, fundingPath));
      included.add(pair);
    }

    if (included.isEmpty()) {
      System.err.println(
          "REFUSED: no pair had kline+funding datasets for label='"
              + label
              + "' — nothing to calibrate");
      return 1;
    }

    var provider =
        ParameterSpaceRegistry.load()
            .find(strategyName)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "unknown --strategy='"
                            + strategyName
                            + "'; known: "
                            + ParameterSpaceRegistry.load().names()));
    List<Candidate> candidates =
        ("grid".equals(searchMode) ? provider.grid(fees) : provider.random(seed, samples, fees))
            .toList();

    Path outDir = Paths.get(Args.opt(args, "out", defaultOutDir()));
    Files.createDirectories(outDir);
    Path progressLog = outDir.resolve("progress.log");

    // trials=0 against the real candidatesRequested, deliberately in that order: an aborted run's
    // manifest must read as incomplete under the "trials < candidatesRequested" rule. Reversing
    // these two was a real defect in PortfolioCalibrateCli, found in review of viglide#22.
    writeManifest(
        outDir,
        args,
        strategyName,
        included,
        skipped,
        0,
        seed,
        searchMode,
        candidates.size(),
        null,
        0,
        parallelism);

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
              throw new UncheckedIOException(e);
            }
          };

      List<PanelCalibrationHarness.PanelResult> ranked =
          PanelCalibrationHarness.run(
              candlesBySymbol,
              fundingBySymbol,
              Map.of(), // no per-pair volatility scaling: off by default, PLAN-013 Task G item 2
              interval,
              cfg,
              candidates.stream(),
              // The candidate already carries its own built strategy, so this CLI never needs to
              // know the concrete type -- which is what keeps a private strategy out of the public
              // repo while still being fittable here.
              (base, scale) -> base.strategy(),
              parallelism,
              timeBudget,
              progress,
              checkpointEvery);

      Path trialRegistryPath = Paths.get(Args.opt(args, "trial-registry", "research-trials.jsonl"));
      String datasetFingerprint =
          TrialRegistry.panelFingerprint(included, interval.name(), datasetsBySymbol);
      TrialRegistry.append(
          trialRegistryPath,
          new TrialRegistry.TrialRecord(
              Instant.now(),
              strategyName,
              datasetFingerprint,
              ranked.size(),
              seed,
              "panel-pooled-yield"));
      int cumulativeTrials =
          TrialRegistry.cumulativeTrialsFor(trialRegistryPath, datasetFingerprint);

      writeManifest(
          outDir,
          args,
          strategyName,
          included,
          skipped,
          ranked.size(),
          seed,
          searchMode,
          candidates.size(),
          datasetFingerprint,
          cumulativeTrials,
          parallelism);
      writeTop(outDir, ranked, topK);
      System.out.printf(
          "panel calibration: candidates=%d evaluated=%d top1_score=%s → %s%n"
              + "trial registry: cumulative for this panel=%d (%s, fingerprint %s)%n",
          candidates.size(),
          ranked.size(),
          ranked.isEmpty() ? "n/a" : String.format("%.5f", ranked.get(0).score()),
          outDir,
          cumulativeTrials,
          trialRegistryPath,
          datasetFingerprint);
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
      int candidatesRequested,
      String datasetFingerprint,
      int cumulativeTrials,
      int parallelism)
      throws IOException {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("startedAt", Instant.now().toString());
    m.put("strategy", strategyName);
    m.put("fitMode", "panel");
    m.put("pairsIncluded", included);
    m.put("pairsSkipped", skipped);
    m.put("seed", seed);
    m.put("searchMode", searchMode);
    m.put("parallelism", parallelism);
    m.put("javaVersion", Runtime.version().toString());
    m.put("candidatesRequested", candidatesRequested);
    m.put("trials", trials);
    m.put("datasetFingerprint", datasetFingerprint);
    m.put("cumulativeTrialsForPanel", cumulativeTrials);
    m.put("args", new LinkedHashMap<>(args));
    Files.writeString(outDir.resolve("manifest.json"), JsonWriter.pretty(m));
  }

  private static void writeTop(
      Path outDir, List<PanelCalibrationHarness.PanelResult> ranked, int topK) throws IOException {
    List<PanelCalibrationHarness.PanelResult> top =
        ranked.subList(0, Math.min(topK, ranked.size()));
    StringBuilder csv = new StringBuilder();
    String metrics =
        "pooled_trade_count,pooled_net_pnl,pooled_return_on_deployed_capital,"
            + "cross_pair_sign_consistency,pairs_with_trades,total_pairs,score\n";
    if (top.isEmpty()) {
      csv.append(metrics);
    } else {
      Set<String> paramKeys = new LinkedHashSet<>(top.get(0).params().keySet());
      for (String k : paramKeys) {
        csv.append(k).append(',');
      }
      csv.append(metrics);
      for (var r : top) {
        for (String k : paramKeys) {
          Object v = r.params().get(k);
          csv.append(v == null ? "" : v.toString()).append(',');
        }
        csv.append(r.pooledTradeCount())
            .append(',')
            .append(r.pooledNetPnl().toPlainString())
            .append(',')
            .append(r.pooledReturnOnDeployedCapital().toPlainString())
            .append(',')
            .append(r.crossPairSignConsistency())
            .append(',')
            .append(r.pairsWithTrades())
            .append(',')
            .append(r.totalPairs())
            .append(',')
            .append(r.score())
            .append('\n');
      }
    }
    Files.writeString(outDir.resolve("top.csv"), csv.toString());

    List<Map<String, Object>> json = new ArrayList<>(top.size());
    for (var r : top) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("params", r.params());
      m.put("pooledTradeCount", r.pooledTradeCount());
      m.put("pooledNetPnl", r.pooledNetPnl());
      m.put("pooledReturnOnDeployedCapital", r.pooledReturnOnDeployedCapital());
      m.put("crossPairSignConsistency", r.crossPairSignConsistency());
      m.put("pairsWithTrades", r.pairsWithTrades());
      m.put("totalPairs", r.totalPairs());
      m.put("pnlByPair", r.pnlByPair());
      m.put("tradeCountByPair", r.tradeCountByPair());
      m.put("score", r.score());
      json.add(m);
    }
    Files.writeString(outDir.resolve("top.json"), JsonWriter.pretty(json));
  }

  private static Duration parseDuration(String s) {
    return (s == null || s.isBlank()) ? null : Duration.parse(s);
  }

  private static String defaultOutDir() {
    return "build/calibrations/panel_"
        + LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
  }
}
