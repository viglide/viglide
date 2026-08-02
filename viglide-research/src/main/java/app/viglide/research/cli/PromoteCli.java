package app.viglide.research.cli;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.BacktestHarness;
import app.viglide.core.backtest.BacktestResult;
import app.viglide.core.backtest.EconomicMetrics;
import app.viglide.core.backtest.FeeModel;
import app.viglide.core.backtest.FundingArbHarness;
import app.viglide.core.backtest.FundingArbHarnessV2;
import app.viglide.core.backtest.Metrics;
import app.viglide.core.backtest.SharpeStats;
import app.viglide.core.data.CsvFundingReader;
import app.viglide.core.data.CsvKlineReader;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.params.CliArgs;
import app.viglide.core.params.JsonReader;
import app.viglide.core.params.JsonWriter;
import app.viglide.core.spi.StrategyKind;
import app.viglide.core.spi.StrategyRegistry;
import app.viglide.core.spi.TradingStrategy;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Promotes the top-ranked calibration result into a local {@code winners.json} that the user
 * (manually) keeps gitignored. This is the local-only stand-in for "promote to GCP Secret Manager"
 * — same workflow, no cloud required while iterating offline.
 *
 * <p>PLAN-008 Task D.3/D.4: also re-runs the winner's params over the <strong>full training
 * window</strong> (the manifest's recorded dataset — not just its cross-validation folds) to
 * compute the Probabilistic and Deflated Sharpe Ratios, and can refuse to promote below an optional
 * {@code --min-dsr} quality floor. A negative-Sharpe or low-DSR promotion is never silent — the
 * review that motivated this found one that was.
 *
 * <p>PLAN-010 Task A1 (D10-2): the default {@code winners.json} root store is <strong>floor-
 * gated</strong> — a candidate with {@code cvSharpeMedian <= 0} is refused (exit 1, a clear {@code
 * REFUSED:} line), never silently promoted. "Promoted" must mean "showed at least in-sample skill",
 * never "least-bad of a bad bunch" (this was found to be exactly what was happening: 46 of 97
 * current-schema root-store entries had {@code cvSharpeMedian <= 0} before this gate existed). The
 * override is deliberately two-factor: {@code --allow-negative} <em>and</em> a {@code --winners}
 * path other than the default root file — bulk research runs (e.g. {@code research_matrix.py},
 * which needs to record failures too) write to a separate, clearly-scoped research store instead,
 * never the root file real promotion decisions read from.
 *
 * <p>Required args:
 *
 * <ul>
 *   <li>{@code --from=<dir>} — calibration output directory containing {@code top.json} and {@code
 *       manifest.json}
 *   <li>{@code --key=<name>} — winners.json entry key (e.g. {@code BTCUSDT_emarsi_2025})
 * </ul>
 *
 * <p>Optional args:
 *
 * <ul>
 *   <li>{@code --rank=<N>} — 1-based rank to promote (default 1)
 *   <li>{@code --select=argmax|plateau} (default {@code argmax}, PLAN-009 Task E, default flipped
 *       PLAN-011 Task H / finding F8) — {@code argmax} keeps {@code top.json}'s own {@code
 *       cvSharpeMedian} order; {@code plateau} re-sorts by {@code plateauScore} (median Sharpe over
 *       a perturbation neighborhood — see {@code PlateauScorer}) before applying {@code --rank}.
 *       <strong>{@code plateau} is advisory-only, not the default</strong>: every strategy family
 *       calibrated so far has a confidence-only parameter that {@code PlateauScorer} cannot
 *       distinguish from one that affects trading, which empirically made {@code plateauScore ==
 *       cvSharpeMedian} in every validated case (E.4) — i.e. it has never yet picked a candidate
 *       {@code argmax} wouldn't have. See {@code PlateauScorer}'s class Javadoc and {@code
 *       docs/runbook.md} §3 for the full decision record.
 *   <li>{@code --winners=<path>} — winners file (default {@code winners.json} at the CWD)
 *   <li>{@code --note=<text>} — free-form annotation stored alongside the params
 *   <li>{@code --min-dsr=<double>} — quality gate. Absent by default (report-only, never blocks);
 *       when set, promotion is refused (non-zero exit) if the computed DSR falls below it.
 *   <li>{@code --allow-negative} — required (together with a non-default {@code --winners}) to
 *       promote a {@code cvSharpeMedian <= 0} candidate anywhere; a no-op combined with the default
 *       winners path, which always refuses regardless (PLAN-010 D10-2).
 * </ul>
 */
public final class PromoteCli {

  private PromoteCli() {}

  public static void main(String[] argv) throws IOException {
    System.exit(run(argv));
  }

  /**
   * Core logic, returning the process exit code (0 = success/added, 1 = refused by {@code
   * --min-dsr}) instead of calling {@link System#exit} directly — {@link #main} does that, so this
   * method can be exercised in-process by tests without killing the test JVM.
   */
  @SuppressWarnings("unchecked")
  static int run(String[] argv) throws IOException {
    Map<String, String> args = Args.parse(argv);
    Path fromDir = Paths.get(Args.require(args, "from"));
    String key = Args.require(args, "key");
    int rank = Args.intOpt(args, "rank", 1);
    Path winnersPath = Paths.get(Args.opt(args, "winners", "winners.json"));
    String note = Args.opt(args, "note", "");
    Double minDsr = args.containsKey("min-dsr") ? Double.parseDouble(args.get("min-dsr")) : null;

    Path topJsonPath = fromDir.resolve("top.json");
    if (!Files.exists(topJsonPath)) {
      throw new IllegalArgumentException("top.json not found in " + fromDir);
    }
    List<Object> top = (List<Object>) JsonReader.parse(Files.readString(topJsonPath));

    // PLAN-009 Task E: --select=plateau re-sorts top.json by plateauScore before picking --rank —
    // an argmax winner can sit on a narrow overfit peak; a candidate whose perturbation
    // neighborhood also scores well is more likely to hold up. --select=argmax (default since
    // PLAN-011 Task H / finding F8: plateau selection is advisory-only, not proven to discriminate
    // for any current strategy family — see PlateauScorer's class Javadoc) keeps the original
    // cvSharpeMedian ranking (top.json's own order). An entry with no plateauScore (older
    // top.json, or beyond the top-20 CalibrateCli scores) sorts last under plateau selection
    // rather than erroring — a missing score is not evidence of a plateau.
    String select = Args.opt(args, "select", "argmax");
    if (!"plateau".equals(select) && !"argmax".equals(select)) {
      throw new IllegalArgumentException(
          "unknown --select='" + select + "'; expected plateau|argmax");
    }
    List<Object> ranked = top;
    if ("plateau".equals(select)) {
      ranked = new ArrayList<>(top);
      ranked.sort(
          Comparator.comparingDouble(
                  (Object o) -> {
                    Object v = ((Map<String, Object>) o).get("plateauScore");
                    return v == null ? Double.NEGATIVE_INFINITY : ((Number) v).doubleValue();
                  })
              .reversed());
    }
    if (rank < 1 || rank > ranked.size()) {
      throw new IllegalArgumentException(
          "--rank=" + rank + " out of range; top.json has " + ranked.size() + " entries");
    }
    Map<String, Object> entry = (Map<String, Object>) ranked.get(rank - 1);

    Path manifestPath = fromDir.resolve("manifest.json");
    if (!Files.exists(manifestPath)) {
      throw new IllegalArgumentException("manifest.json not found in " + fromDir);
    }
    Map<String, Object> manifest =
        (Map<String, Object>) JsonReader.parse(Files.readString(manifestPath));

    double cvSharpeMedian = numberOf(Args.jsonFallback(entry, "cvSharpeMedian", "oosSharpeMedian"));
    PsrDsr psrDsr = computePsrDsr(manifest, entry);

    // PLAN-013 Task B (finding F14): a clamped radicand still yields a finite psr/dsr (that's the
    // point of the clamp), but a reader must be able to tell it apart from a normally-computed one.
    if (psrDsr.psrRadicandClamped()) {
      System.out.println(
          String.format(
              "WARNING: psr radicand clamped for key=%s — extreme skew/kurtosis combination;"
                  + " psr/dsr are finite but statistically degenerate, not a normal-regime result",
              key));
    }
    // D.4: a negative-Sharpe or low-DSR promotion must never be silent again.
    if (psrDsr.dsr() < 0.5) {
      System.out.println(
          String.format(
              "WARNING: dsr=%.4f < 0.5 for key=%s — weak/no evidence of skill after correcting"
                  + " for %d calibration trials",
              psrDsr.dsr(), key, psrDsr.trials()));
    }
    if (cvSharpeMedian < 0) {
      System.out.println(
          String.format(
              "WARNING: cvSharpeMedian=%.4f < 0 for key=%s — promoting a candidate that lost"
                  + " money even in cross-validation",
              cvSharpeMedian, key));
    }

    // PLAN-010 Task A1 (D10-2): the promotion floor. Two-factor override — --allow-negative alone
    // is not enough if the target is still the default root file, and a non-default --winners path
    // alone is not enough without --allow-negative either; both must be true.
    boolean allowNegative = args.containsKey("allow-negative");
    if (cvSharpeMedian <= 0 && (!allowNegative || isDefaultRootWinnersPath(winnersPath))) {
      System.err.println(
          String.format(
              "REFUSED: cvSharpeMedian=%.4f <= 0 for key=%s — promotion floor (PLAN-010 D10-2):"
                  + " a candidate must show at least in-sample skill to be promoted. Override"
                  + " requires BOTH --allow-negative AND a --winners path other than the default"
                  + " root winners.json (e.g. a research-scoped store).",
              cvSharpeMedian, key));
      return 1;
    }

    if (minDsr != null && psrDsr.dsr() < minDsr) {
      System.err.println(
          String.format(
              "REFUSED: dsr=%.4f < --min-dsr=%.4f for key=%s — promotion blocked",
              psrDsr.dsr(), minDsr, key));
      return 1;
    }

    Map<String, Object> winners = loadWinners(winnersPath);

    Map<String, Object> record = new LinkedHashMap<>();
    record.put("promotedAt", Instant.now().toString());
    // PLAN-018 R-3: repo-relative, never absolute — an absolute path embeds the operator's
    // machine username (CLAUDE.md §8a condition 5) and winners.json is tracked in the private
    // repo from this point on.
    record.put("source", repoRelative(fromDir));
    record.put("rank", rank);
    record.put("note", note);
    record.put("params", entry.get("params"));
    // D.2: new entries always write the cv* spelling, never the legacy oos*.
    record.put("cvSharpeMedian", Args.jsonFallback(entry, "cvSharpeMedian", "oosSharpeMedian"));
    record.put(
        "cvTotalReturnMedian",
        Args.jsonFallback(entry, "cvTotalReturnMedian", "oosTotalReturnMedian"));
    record.put(
        "cvMaxDrawdownWorst",
        Args.jsonFallback(entry, "cvMaxDrawdownWorst", "oosMaxDrawdownWorst"));
    record.put(
        "cvTradeCountMedian",
        Args.jsonFallback(entry, "cvTradeCountMedian", "oosTradeCountMedian"));
    record.put("foldsEvaluated", entry.get("foldsEvaluated"));
    // D.3: PSR/DSR from a deterministic re-run of the winner over the full training window.
    record.put("psr", psrDsr.psr());
    record.put("psrRadicandClamped", psrDsr.psrRadicandClamped()); // PLAN-013 Task B, finding F14
    record.put("dsr", psrDsr.dsr());
    record.put("trials", psrDsr.trials());
    // PLAN-013 Task A (finding F2): reported alongside psr/dsr, never in place of them.
    record.put("returnOnDeployedCapital", psrDsr.returnOnDeployedCapital());
    record.put("deployedOnlySharpe", psrDsr.deployedOnlySharpe());

    Map<String, Object> previous = (Map<String, Object>) winners.get(key);
    winners.put(key, record);

    Files.writeString(winnersPath, JsonWriter.pretty(winners));
    System.out.println(
        String.format(
            "promote: key=%s rank=%d psr=%.4f dsr=%.4f trials=%d %s → %s",
            key,
            rank,
            psrDsr.psr(),
            psrDsr.dsr(),
            psrDsr.trials(),
            previous == null ? "added" : "replaced",
            winnersPath));
    return 0;
  }

  /**
   * PSR/DSR (plus PLAN-013 Task A's economic metrics) for the winner, from a deterministic re-run
   * over the full training window. {@code psrRadicandClamped}: PLAN-013 Task B, finding F14.
   */
  private record PsrDsr(
      double psr,
      boolean psrRadicandClamped,
      double dsr,
      int trials,
      BigDecimal returnOnDeployedCapital,
      double deployedOnlySharpe) {}

  /**
   * Re-runs the winner's params over the manifest's full training dataset (deterministic — same
   * strategy, same config, same data as calibration used, just without the fold split) and computes
   * PSR/DSR from the resulting equity curve. Ungated (no Risk Manager): this is a statistical
   * evaluation of the strategy's own signal quality, not a trading decision.
   */
  @SuppressWarnings("unchecked")
  private static PsrDsr computePsrDsr(Map<String, Object> manifest, Map<String, Object> entry)
      throws IOException {
    Map<String, Object> manifestArgs = (Map<String, Object>) manifest.get("args");
    String strategyName = stringOf(manifest.get("strategy"));
    String datasetPath = stringArg(manifestArgs, "dataset");
    if (datasetPath == null) {
      throw new IllegalArgumentException("manifest.json has no args.dataset to re-run against");
    }
    String symbol = stringArg(manifestArgs, "symbol");
    CandleInterval interval = CandleInterval.valueOf(stringArg(manifestArgs, "interval"));

    BacktestConfig baseCfg = BacktestConfig.hourlyDefaults();
    BacktestConfig cfg =
        new BacktestConfig(
            baseCfg.startingCash(),
            FeeModel.binanceDefault(),
            intArg(manifestArgs, "warmup-bars", baseCfg.warmupBars()),
            baseCfg.maxPositionPct(),
            baseCfg.stopLossPct(),
            baseCfg.takeProfitPct(),
            intArg(manifestArgs, "bars-per-year", BacktestConfig.barsPerYearFor(interval)));

    Map<String, Object> params = (Map<String, Object>) entry.get("params");
    TradingStrategy strategy =
        StrategyRegistry.load().create(strategyName, CliArgs.camelMapToCliArgs(params));

    // PLAN-008 Task F: FundingArbParameterSpace threads minHoldBars through the params snapshot
    // even though it is a harness knob, not a FundingArbParameters field — re-apply it here so the
    // re-run matches what calibration actually evaluated for this candidate.
    Object minHoldBarsVal = params.get("minHoldBars");
    if (minHoldBarsVal != null) {
      cfg =
          new BacktestConfig(
              cfg.startingCash(),
              cfg.fees(),
              cfg.warmupBars(),
              cfg.maxPositionPct(),
              cfg.stopLossPct(),
              cfg.takeProfitPct(),
              cfg.barsPerYear(),
              ((Number) minHoldBarsVal).intValue());
    }

    List<Candle> candles;
    try (Stream<Candle> s = CsvKlineReader.stream(Paths.get(datasetPath))) {
      candles = s.toList();
    }

    BacktestResult result;
    if (strategy.metadata().kind() == StrategyKind.FUNDING_AWARE) {
      String fundingDatasetPath = stringArg(manifestArgs, "funding-dataset");
      if (fundingDatasetPath == null) {
        throw new IllegalArgumentException(
            "manifest.json has no args.funding-dataset but strategy '"
                + strategyName
                + "' is FUNDING_AWARE");
      }
      List<FundingEvent> funding;
      try (Stream<FundingEvent> s = CsvFundingReader.stream(Paths.get(fundingDatasetPath))) {
        funding = s.toList();
      }
      // v2 needs a spot leg; its presence in the manifest is a more reliable signal than a
      // possibly-absent --funding-model flag (which only records what was *typed*, not defaults;
      // an old, pre-Task-F manifest never has a spot-dataset at all — correctly falls back to v1).
      String spotDatasetPath = stringArg(manifestArgs, "spot-dataset");
      if (spotDatasetPath != null) {
        List<Candle> spotCandles;
        try (Stream<Candle> s = CsvKlineReader.stream(Paths.get(spotDatasetPath))) {
          spotCandles = s.toList();
        }
        result =
            FundingArbHarnessV2.run(strategy, candles, spotCandles, funding, symbol, interval, cfg);
      } else {
        result = FundingArbHarness.run(strategy, candles, funding, symbol, interval, cfg);
      }
    } else {
      result = BacktestHarness.run(strategy, candles.stream(), symbol, interval, cfg);
    }

    SharpeStats stats = Metrics.sharpeStats(result.equityCurve());
    int trials = intOf(manifest.getOrDefault("trials", 0));
    double trialVariance = numberOf(manifest.getOrDefault("trialSharpeVariancePerPeriod", 0.0));
    Metrics.PsrResult psrResult = Metrics.probabilisticSharpeRatioDetailed(stats, 0.0);
    double dsr = Metrics.deflatedSharpeRatio(stats, trials, trialVariance);
    // PLAN-013 Task A (finding F2): reported alongside psr/dsr, never in place of them.
    BigDecimal returnOnDeployedCapital = EconomicMetrics.returnOnDeployedCapital(result);
    double deployedOnlySharpe =
        EconomicMetrics.deployedOnlySharpe(result.equityCurve(), result.trades());
    return new PsrDsr(
        psrResult.probability(),
        psrResult.radicandClamped(),
        dsr,
        trials,
        returnOnDeployedCapital,
        deployedOnlySharpe);
  }

  /**
   * PLAN-010 Task A1: {@code true} when {@code winnersPath} resolves to the same file as the
   * literal default (the {@code winners.json} the promotion floor protects), whether that came from
   * an explicit {@code --winners=winners.json} or from the CLI default. Path-normalized comparison,
   * not string equality, so {@code ./winners.json} still counts.
   */
  static boolean isDefaultRootWinnersPath(Path winnersPath) {
    return winnersPath
        .toAbsolutePath()
        .normalize()
        .equals(Paths.get("winners.json").toAbsolutePath().normalize());
  }

  /**
   * Repo-relative rendering of {@code path}, resolved against the current working directory (the
   * repo root when invoked via {@code ./gradlew :viglide-research:promote}, per that task's own
   * {@code workingDir = rootProject.projectDir}) — never an absolute path (PLAN-018 R-3).
   */
  static String repoRelative(Path path) {
    Path cwd = Paths.get("").toAbsolutePath().normalize();
    Path target = path.toAbsolutePath().normalize();
    return cwd.relativize(target).toString();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadWinners(Path winnersPath) throws IOException {
    if (!Files.exists(winnersPath) || Files.size(winnersPath) == 0) {
      return new LinkedHashMap<>();
    }
    Object root = JsonReader.parse(Files.readString(winnersPath));
    if (!(root instanceof Map<?, ?> m)) {
      throw new IllegalArgumentException("winners file is not a JSON object: " + winnersPath);
    }
    return new LinkedHashMap<>((Map<String, Object>) m);
  }

  // ── small JSON-map accessors ─────────────────────────────────────────────────────────────────

  private static double numberOf(Object v) {
    return v == null ? 0.0 : ((Number) v).doubleValue();
  }

  private static int intOf(Object v) {
    return v == null ? 0 : ((Number) v).intValue();
  }

  private static String stringOf(Object v) {
    return v == null ? null : v.toString();
  }

  private static String stringArg(Map<String, Object> argsMap, String key) {
    if (argsMap == null) return null;
    Object v = argsMap.get(key);
    return v == null || v.toString().isBlank() ? null : v.toString();
  }

  private static int intArg(Map<String, Object> argsMap, String key, int fallback) {
    String v = stringArg(argsMap, key);
    return v == null ? fallback : Integer.parseInt(v);
  }
}
