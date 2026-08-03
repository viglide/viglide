package app.viglide.research.cli;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.BacktestResult;
import app.viglide.core.backtest.EconomicMetrics;
import app.viglide.core.backtest.EquityPoint;
import app.viglide.core.backtest.FeeModel;
import app.viglide.core.backtest.Metrics;
import app.viglide.core.backtest.PortfolioBacktestHarness;
import app.viglide.core.backtest.PortfolioFundingArbHarnessV2;
import app.viglide.core.backtest.Refusal;
import app.viglide.core.backtest.SharpeStats;
import app.viglide.core.backtest.Trade;
import app.viglide.core.data.CsvFundingReader;
import app.viglide.core.data.CsvKlineReader;
import app.viglide.core.data.CsvPremiumIndexReader;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.domain.PremiumIndexEvent;
import app.viglide.core.params.CliArgs;
import app.viglide.core.params.JsonReader;
import app.viglide.core.params.JsonWriter;
import app.viglide.core.risk.BacktestClockSync;
import app.viglide.core.risk.MutableClock;
import app.viglide.core.risk.RiskManager;
import app.viglide.core.risk.RiskManagerPort;
import app.viglide.core.risk.RiskParameters;
import app.viglide.core.spi.StrategyKind;
import app.viglide.core.spi.StrategyRegistry;
import app.viglide.core.spi.TradingStrategy;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Command-line entry point for a multi-symbol portfolio backtest (PLAN-008 Task H). Unlike {@link
 * BacktestCli} — one symbol, one strategy instance, no cross-symbol exposure check — this drives
 * {@link PortfolioBacktestHarness} (v1) or {@link PortfolioFundingArbHarnessV2} (v2): every pair
 * shares one cash balance and one gate, so CLAUDE.md §7's leverage and circuit-breaker limits are
 * exercised at the portfolio scope they actually apply to. There is no ungated mode (CLAUDE.md §11)
 * — the Risk Manager is always wired.
 *
 * <p>Required args:
 *
 * <ul>
 *   <li>{@code --pairs=BTCUSDT,ETHUSDT,…} — symbols to include
 *   <li>{@code --strategy=fundingarb} — strategy short name (one {@link StrategyRegistry} entry,
 *       applied to every pair; per-pair parameters still come from each pair's own winners.json
 *       entry)
 *   <li>{@code --label=2024} — OOS year label (or {@code 2026H1}); resolves each pair's dataset as
 *       {@code {datasets-dir}/{PAIR}_{intervalTag}_{label}.csv} (and {@code
 *       {PAIR}_funding_{label}.csv} for a {@link StrategyKind#FUNDING_AWARE} strategy)
 * </ul>
 *
 * <p>Optional args:
 *
 * <ul>
 *   <li>{@code --interval=ONE_HOUR} (PLAN-011 Task F, finding F6): decision cadence for every pair
 *       — was hardcoded to {@code ONE_HOUR}, now any {@link CandleInterval} (H3 asks about {@code
 *       FIFTEEN_MINUTES} specifically). Determines both harnesses' behaviour and the dataset
 *       filename tag ({@code 1h}, {@code 15m}, …).
 *   <li>{@code --funding-model=v1} (PLAN-011 Task F): {@code v1} (default, unchanged) is {@link
 *       PortfolioBacktestHarness}'s full-price-exposure model; {@code v2} switches to {@link
 *       PortfolioFundingArbHarnessV2}'s delta-neutral two-leg model (margin, liquidation guard,
 *       true shared-margin RM gating) and requires each pair's spot dataset too ({@code
 *       {PAIR}_spot_{intervalTag}_{label}.csv} — a pair missing it is skipped like any other gap).
 *   <li>{@code --sub-bar-realism=true} (PLAN-011 Task F, {@code v2} only, ignored at {@code
 *       ONE_MINUTE}): loads each pair's {@code {PAIR}_1m_{label}.csv} as the liquidation guard's
 *       finer-grained series (PLAN-009 Task B2) — a pair missing that file keeps close-only timing
 *       for that pair alone (warning, not a skip).
 *   <li>Premium-index nowcast data (PLAN-015 Task C, {@code --funding-model=v2} only): loaded
 *       automatically from {@code {PAIR}_premiumidx_{intervalTag}_{label}.csv} when present, no
 *       flag needed — optional and non-fatal per pair (falls back to realised-history-only).
 *   <li>{@code --datasets-dir=data} (default {@code data})
 *   <li>{@code --winners-template={PAIR}_fundingarb_{Y}} (default {@code {PAIR}_<strategy>_{Y}});
 *       {@code {Y}} is the training-year key into winners.json. Defaults to the PLAN-008 1-year-
 *       ahead convention ({@code trainYear = label − 1}, or {@code 2025} when {@code label} is
 *       {@code 2026H1}) — override with {@code --train-label} when a run doesn't follow it.
 *   <li>{@code --train-label=2023} — explicit override for {@code {Y}}, instead of deriving it
 *   <li>{@code --winners=winners.json}, {@code --starting-cash}, {@code --fee-scale}, {@code
 *       --fee-mode}, {@code --warmup-bars}, {@code --rm-*} (same semantics as {@link BacktestCli})
 *   <li>{@code --out=…} (default {@code build/backtests/portfolio_<strategy>_<label>oos}, with an
 *       {@code _<intervalTag>} suffix when {@code --interval} isn't {@code ONE_HOUR}, a {@code _v2}
 *       suffix for {@code --funding-model=v2}, and a {@code _maker} suffix for {@code
 *       --fee-mode=maker} — all only when {@code --out} was not given explicitly)
 * </ul>
 *
 * <p>A pair with no matching winners.json entry for the derived training year is skipped with a
 * warning — never silently — and the actually-included pair list is recorded in {@code result.json}
 * so a partial-coverage run is never mistaken for a full one.
 */
public final class PortfolioCli {

  private static final BigDecimal DEFAULT_FEE_SCALE = BigDecimal.ONE;
  private static final String DEFAULT_FEE_MODE = "taker";
  private static final String DEFAULT_FUNDING_MODEL = "v1";

  // PLAN-011 Task F: on-disk filename tag per interval (matches scripts/research_matrix.py's
  // INTERVAL_TAG and download_historical_data.py's _csv_name convention).
  private static final Map<CandleInterval, String> INTERVAL_TAG =
      Map.of(
          CandleInterval.ONE_DAY, "1d",
          CandleInterval.FOUR_HOURS, "4h",
          CandleInterval.ONE_HOUR, "1h",
          CandleInterval.FIFTEEN_MINUTES, "15m",
          CandleInterval.FIVE_MINUTES, "5m",
          CandleInterval.ONE_MINUTE, "1m");

  private PortfolioCli() {}

  public static void main(String[] argv) throws IOException {
    System.exit(run(argv));
  }

  // Public (not package-private): the fundingarb-specific tests (viglide-strategies, private repo,
  // PLAN-018 R-5) call this directly instead of going through main()'s System.exit.
  public static int run(String[] argv) throws IOException {
    Map<String, String> args = Args.parse(argv);

    List<String> pairs = List.of(Args.require(args, "pairs").split(","));
    String strategyName = Args.require(args, "strategy");
    String label = Args.require(args, "label");
    Path datasetsDir = Paths.get(Args.opt(args, "datasets-dir", "data"));
    Path winnersPath = Paths.get(Args.opt(args, "winners", "winners.json"));
    String trainLabel = Args.opt(args, "train-label", trainYearForOosLabel(label));
    String winnersTemplate = Args.opt(args, "winners-template", "{PAIR}_" + strategyName + "_{Y}");
    // PLAN-011 Task F (finding F6): arbitrary decision cadence -- was hardcoded to ONE_HOUR both
    // here and in the dataset-path construction below.
    CandleInterval interval = CandleInterval.valueOf(Args.opt(args, "interval", "ONE_HOUR"));
    String intervalTag = INTERVAL_TAG.get(interval);
    // PLAN-011 Task F: the v2 two-leg (spot+perp, margin, liquidation guard) model, shared across
    // the whole portfolio -- see PortfolioFundingArbHarnessV2's class Javadoc for why v1 (this
    // CLI's original and still-default behaviour) cannot answer H3 for real.
    String fundingModel = Args.opt(args, "funding-model", DEFAULT_FUNDING_MODEL);
    if (!"v1".equals(fundingModel) && !"v2".equals(fundingModel)) {
      throw new IllegalArgumentException(
          "unknown --funding-model='" + fundingModel + "'; expected v1|v2");
    }
    boolean subBarRealism =
        Args.flag(args, "sub-bar-realism") && interval != CandleInterval.ONE_MINUTE;

    @SuppressWarnings("unchecked")
    Map<String, Object> winners =
        Files.exists(winnersPath)
            ? (Map<String, Object>) JsonReader.parse(Files.readString(winnersPath))
            : Map.of();

    FeeModel fees = buildFees(args);
    BacktestConfig cfg =
        new BacktestConfig(
            Args.bigDecOpt(args, "starting-cash", new BigDecimal("10000")),
            fees,
            Args.intOpt(args, "warmup-bars", "fundingarb".equals(strategyName) ? 100 : 200),
            BigDecimal.ONE, // maxPositionPct: unused by PortfolioBacktestHarness (RM sizes instead)
            null,
            null,
            Args.intOpt(args, "bars-per-year", BacktestConfig.barsPerYearFor(interval)));
    RiskManagerPort rm = buildRiskManager(args);
    StrategyRegistry strategyRegistry = StrategyRegistry.load();

    Map<String, List<Candle>> candlesBySymbol = new LinkedHashMap<>();
    Map<String, List<Candle>> spotBySymbol = new LinkedHashMap<>();
    Map<String, List<Candle>> subBarBySymbol = new LinkedHashMap<>();
    Map<String, TradingStrategy> strategiesBySymbol = new LinkedHashMap<>();
    Map<String, List<FundingEvent>> fundingBySymbol = new LinkedHashMap<>();
    Map<String, List<PremiumIndexEvent>> premiumIndexBySymbol = new LinkedHashMap<>();
    List<String> included = new ArrayList<>();
    List<String> skipped = new ArrayList<>();

    for (String pair : pairs) {
      String key = winnersTemplate.replace("{PAIR}", pair).replace("{Y}", trainLabel);
      @SuppressWarnings("unchecked")
      Map<String, Object> entry = (Map<String, Object>) winners.get(key);
      if (entry == null) {
        System.out.println("[SKIP] no winners.json entry for '" + key + "' — excluding " + pair);
        skipped.add(pair);
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> params = (Map<String, Object>) entry.get("params");
      TradingStrategy strategy =
          strategyRegistry.create(strategyName, CliArgs.camelMapToCliArgs(params));

      Path klinePath = datasetsDir.resolve(pair + "_" + intervalTag + "_" + label + ".csv");
      if (!Files.exists(klinePath)) {
        System.out.println("[SKIP] no dataset " + klinePath + " — excluding " + pair);
        skipped.add(pair);
        continue;
      }
      List<Candle> candles;
      try (Stream<Candle> s = CsvKlineReader.stream(klinePath)) {
        candles = s.toList();
      }

      if ("v2".equals(fundingModel)) {
        Path spotPath = datasetsDir.resolve(pair + "_spot_" + intervalTag + "_" + label + ".csv");
        if (!Files.exists(spotPath)) {
          System.out.println("[SKIP] no spot dataset " + spotPath + " — excluding " + pair);
          skipped.add(pair);
          continue;
        }
        List<Candle> spotCandles;
        try (Stream<Candle> s = CsvKlineReader.stream(spotPath)) {
          spotCandles = s.toList();
        }
        spotBySymbol.put(pair, spotCandles);

        if (subBarRealism) {
          Path subBarPath = datasetsDir.resolve(pair + "_1m_" + label + ".csv");
          if (Files.exists(subBarPath)) {
            try (Stream<Candle> s = CsvKlineReader.stream(subBarPath)) {
              subBarBySymbol.put(pair, s.toList());
            }
          } else {
            System.out.println(
                "[WARN] no sub-bar dataset "
                    + subBarPath
                    + " — "
                    + pair
                    + " keeps close-only"
                    + " liquidation timing");
          }
        }
      }

      List<FundingEvent> funding = List.of();
      if (strategy.metadata().kind() == StrategyKind.FUNDING_AWARE) {
        Path fundingPath = datasetsDir.resolve(pair + "_funding_" + label + ".csv");
        if (!Files.exists(fundingPath)) {
          System.out.println("[SKIP] no funding dataset " + fundingPath + " — excluding " + pair);
          skipped.add(pair);
          continue;
        }
        try (Stream<FundingEvent> s = CsvFundingReader.stream(fundingPath)) {
          funding = s.toList();
        }

        // PLAN-015 Task C: premium-index nowcast series, optional and non-fatal -- a pair missing
        // it keeps an empty premiumIndexHistory and its strategy is expected to fall back to
        // realised funding history, never excluded the way a missing funding file excludes a
        // pair. Only the v2 harness accepts the series at all, so don't parse a dense CSV per
        // pair that PortfolioBacktestHarness would then discard.
        if ("v2".equals(fundingModel)) {
          Path premiumIndexPath =
              datasetsDir.resolve(pair + "_premiumidx_" + intervalTag + "_" + label + ".csv");
          if (Files.exists(premiumIndexPath)) {
            try (Stream<PremiumIndexEvent> s = CsvPremiumIndexReader.stream(premiumIndexPath)) {
              premiumIndexBySymbol.put(pair, s.toList());
            }
          } else {
            System.out.println(
                "[WARN] no premium-index dataset "
                    + premiumIndexPath
                    + " — "
                    + pair
                    + " falls back to realised-funding history only");
          }
        }
      }

      candlesBySymbol.put(pair, candles);
      strategiesBySymbol.put(pair, strategy);
      fundingBySymbol.put(pair, funding);
      included.add(pair);
    }

    if (included.isEmpty()) {
      System.err.println(
          "REFUSED: no pair had both a winners.json entry and a dataset for "
              + "label='"
              + label
              + "' (train label='"
              + trainLabel
              + "') — nothing to backtest");
      return 1;
    }

    BacktestResult result =
        "v2".equals(fundingModel)
            ? PortfolioFundingArbHarnessV2.run(
                candlesBySymbol,
                spotBySymbol,
                fundingBySymbol,
                premiumIndexBySymbol,
                strategiesBySymbol,
                subBarBySymbol,
                interval,
                cfg,
                rm)
            : PortfolioBacktestHarness.run(
                candlesBySymbol, strategiesBySymbol, fundingBySymbol, interval, cfg, rm);

    Path outDir =
        Paths.get(
            Args.opt(
                args, "out", defaultOutDir(strategyName, label, interval, fundingModel, args)));
    Files.createDirectories(outDir);
    writeOutputs(
        outDir, result, cfg, strategyName, label, included, skipped, args, interval, fundingModel);
    System.out.println(summaryLine(result, included, skipped, outDir));
    return 0;
  }

  /**
   * PLAN-008's fixed 1-year-ahead OOS convention (matching {@code research_matrix.py}'s {@code
   * oos_label}, inverted): a training year's promoted params are tested on the following year's
   * data, and 2025's are tested on the current partial year, {@code 2026H1}.
   */
  private static String trainYearForOosLabel(String oosLabel) {
    if ("2026H1".equalsIgnoreCase(oosLabel)) {
      return "2025";
    }
    return String.valueOf(Integer.parseInt(oosLabel) - 1);
  }

  private static void writeOutputs(
      Path outDir,
      BacktestResult result,
      BacktestConfig cfg,
      String strategyName,
      String label,
      List<String> included,
      List<String> skipped,
      Map<String, String> args,
      CandleInterval interval,
      String fundingModel)
      throws IOException {
    SharpeStats stats = Metrics.sharpeStats(result.equityCurve());

    Map<String, Object> resultJson = new LinkedHashMap<>();
    resultJson.put("strategy", strategyName);
    resultJson.put("label", label);
    resultJson.put("interval", interval.name());
    resultJson.put("fundingModel", fundingModel);
    resultJson.put(
        "subBarResolution", !"v1".equals(fundingModel) && Args.flag(args, "sub-bar-realism"));
    resultJson.put("pairsIncluded", included);
    resultJson.put("pairsSkipped", skipped);
    resultJson.put("startingEquity", result.startingEquity());
    resultJson.put("endingEquity", result.endingEquity());
    resultJson.put("totalReturn", result.totalReturn());
    resultJson.put("sharpe", result.sharpe());
    resultJson.put("maxDrawdown", result.maxDrawdown());
    resultJson.put("winRate", result.winRate());
    resultJson.put("tradeCount", result.tradeCount());
    resultJson.put("refusals", result.refusals().size());
    Metrics.PsrResult psr = Metrics.probabilisticSharpeRatioDetailed(stats, 0.0);
    resultJson.put("psr", psr.probability());
    resultJson.put("psrRadicandClamped", psr.radicandClamped()); // PLAN-013 Task B, finding F14
    resultJson.put("dailyObservations", stats.observations());
    // PLAN-013 Task A (finding F2) -- see docs/runbook.md §6.
    resultJson.put("returnOnDeployedCapital", EconomicMetrics.returnOnDeployedCapital(result));
    resultJson.put("deploymentRatio", EconomicMetrics.deploymentRatio(result));
    resultJson.put(
        "deployedOnlySharpe",
        EconomicMetrics.deployedOnlySharpe(result.equityCurve(), result.trades()));
    resultJson.put("ulcerIndex", EconomicMetrics.ulcerIndex(result.equityCurve()));
    resultJson.put("calmar", EconomicMetrics.calmar(result));
    resultJson.put("feeScale", Args.bigDecOpt(args, "fee-scale", DEFAULT_FEE_SCALE));
    resultJson.put("feeMode", Args.opt(args, "fee-mode", DEFAULT_FEE_MODE));
    resultJson.put("config", configToMap(cfg));
    resultJson.put("diagnostics", new LinkedHashMap<>(result.diagnostics()));

    Files.writeString(outDir.resolve("result.json"), JsonWriter.pretty(resultJson));
    Files.writeString(outDir.resolve("trades.csv"), tradesCsv(result.trades()));
    Files.writeString(outDir.resolve("equity.csv"), equityCsv(result.equityCurve()));
    Files.writeString(outDir.resolve("refusals.csv"), refusalsCsv(result.refusals()));
  }

  private static String tradesCsv(List<Trade> trades) {
    StringBuilder sb = new StringBuilder();
    sb.append("entry_time,exit_time,side,entry_price,exit_price,size,pnl,exit_reason\n");
    for (Trade t : trades) {
      sb.append(t.entryTime())
          .append(',')
          .append(t.exitTime())
          .append(',')
          .append(t.side())
          .append(',')
          .append(t.entryPrice().toPlainString())
          .append(',')
          .append(t.exitPrice().toPlainString())
          .append(',')
          .append(t.size().toPlainString())
          .append(',')
          .append(t.pnl().toPlainString())
          .append(',')
          .append(t.exitReason())
          .append('\n');
    }
    return sb.toString();
  }

  private static String refusalsCsv(List<Refusal> refusals) {
    StringBuilder sb = new StringBuilder();
    sb.append("time,symbol,side,reason,explanation\n");
    for (Refusal r : refusals) {
      sb.append(r.time())
          .append(',')
          .append(r.symbol())
          .append(',')
          .append(r.wantedSide())
          .append(',')
          .append(r.reason())
          .append(',')
          .append(csvEscape(r.explanation()))
          .append('\n');
    }
    return sb.toString();
  }

  private static String csvEscape(String field) {
    if (field.indexOf(',') < 0) {
      return field;
    }
    return "\"" + field.replace("\"", "\"\"") + "\"";
  }

  private static String equityCsv(List<EquityPoint> curve) {
    StringBuilder sb = new StringBuilder();
    sb.append("timestamp,equity\n");
    for (EquityPoint p : curve) {
      sb.append(p.at()).append(',').append(p.equity().toPlainString()).append('\n');
    }
    return sb.toString();
  }

  private static Map<String, Object> configToMap(BacktestConfig cfg) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("startingCash", cfg.startingCash());
    m.put("warmupBars", cfg.warmupBars());
    m.put("barsPerYear", cfg.barsPerYear());
    Map<String, Object> feesMap = new LinkedHashMap<>();
    feesMap.put("makerBps", cfg.fees().makerBps());
    feesMap.put("takerBps", cfg.fees().takerBps());
    feesMap.put("slippageBps", cfg.fees().slippageBps());
    m.put("fees", feesMap);
    return m;
  }

  private static String summaryLine(
      BacktestResult r, List<String> included, List<String> skipped, Path outDir) {
    return String.format(
        "portfolio: pairs=%d (skipped=%d) trades=%d totalReturn=%s sharpe=%.3f maxDrawdown=%s"
            + " winRate=%.3f%s → %s",
        included.size(),
        skipped.size(),
        r.tradeCount(),
        r.totalReturn().toPlainString(),
        r.sharpe(),
        r.maxDrawdown().toPlainString(),
        r.winRate(),
        smallNFlag(r.tradeCount()),
        outDir);
  }

  /**
   * PLAN-010 D10-4: a trailing, grep-stable flag on the console summary so a zero- or thin-trade
   * result is never mistaken for a normal one at a glance — {@code result.json}'s {@code
   * tradeCount} already carries the raw number for programmatic readers; see {@link
   * Metrics#MIN_TRADES_FOR_STATS}.
   */
  private static String smallNFlag(int tradeCount) {
    if (tradeCount == 0) return " [idle]";
    if (tradeCount < Metrics.MIN_TRADES_FOR_STATS)
      return " [n<" + Metrics.MIN_TRADES_FOR_STATS + ", noise]";
    return "";
  }

  private static String defaultOutDir(
      String strategyName,
      String label,
      CandleInterval interval,
      String fundingModel,
      Map<String, String> args) {
    String base = "build/backtests/portfolio_" + strategyName + "_" + label + "oos";
    if (interval != CandleInterval.ONE_HOUR) {
      base += "_" + INTERVAL_TAG.get(interval);
    }
    if ("v2".equals(fundingModel)) {
      base += "_v2";
    }
    return "maker".equalsIgnoreCase(Args.opt(args, "fee-mode", DEFAULT_FEE_MODE))
        ? base + "_maker"
        : base;
  }

  // ── fee model (mirrors BacktestCli's PLAN-008 Task E flags) ────────────────────────────────────

  private static FeeModel buildFees(Map<String, String> args) {
    String feeMode = Args.opt(args, "fee-mode", DEFAULT_FEE_MODE);
    BigDecimal feeScale = Args.bigDecOpt(args, "fee-scale", DEFAULT_FEE_SCALE);
    FeeModel base =
        switch (feeMode.toLowerCase(java.util.Locale.ROOT)) {
          case "taker" -> FeeModel.taker();
          case "maker" -> FeeModel.maker();
          default ->
              throw new IllegalArgumentException(
                  "unknown --fee-mode='" + feeMode + "'; expected taker|maker");
        };
    return base.scaled(feeScale, app.viglide.core.indicator.IndicatorMath.MC);
  }

  // ── risk manager: always on (CLAUDE.md §11 — no ungated portfolio mode) ────────────────────────

  private static RiskManagerPort buildRiskManager(Map<String, String> args) {
    RiskParameters rp =
        new RiskParameters(
            Args.bigDecOpt(args, "rm-max-position-pct", new BigDecimal("0.02")),
            Args.bigDecOpt(args, "rm-max-drawdown-pct", new BigDecimal("0.15")),
            Args.bigDecOpt(args, "rm-max-leverage", new BigDecimal("2.0")),
            Args.bigDecOpt(args, "rm-max-daily-volume-pct", new BigDecimal("0.005")),
            Args.bigDecOpt(args, "rm-max-portfolio-risk-pct", new BigDecimal("0.005")),
            Args.doubleOpt(args, "rm-stop-loss-atr-mult", 2.0),
            Args.doubleOpt(args, "rm-confidence-floor", 0.5),
            Duration.ofMinutes(Args.longOpt(args, "rm-max-stale-input-age-minutes", 90)),
            // PLAN-012 Task F: absolute-dollar limits are a live-phase-only knob, deliberately
            // not exposed as CLI flags here -- research/backtest runs stay unaffected (disabled).
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    MutableClock clock = new MutableClock(Instant.EPOCH, ZoneOffset.UTC);
    RiskManager rm = new RiskManager(rp, clock);
    return new BacktestClockSync(rm, clock);
  }
}
