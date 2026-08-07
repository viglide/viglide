package app.viglide.research.cli;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.BacktestHarness;
import app.viglide.core.backtest.BacktestResult;
import app.viglide.core.backtest.EconomicMetrics;
import app.viglide.core.backtest.EquityPoint;
import app.viglide.core.backtest.FeeModel;
import app.viglide.core.backtest.FundingArbHarness;
import app.viglide.core.backtest.FundingArbHarnessV2;
import app.viglide.core.backtest.Metrics;
import app.viglide.core.backtest.Refusal;
import app.viglide.core.backtest.SharpeStats;
import app.viglide.core.backtest.Trade;
import app.viglide.core.data.CsvFundingReader;
import app.viglide.core.data.CsvKlineReader;
import app.viglide.core.data.CsvPremiumIndexReader;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.ExchangeFilters;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.domain.PremiumIndexEvent;
import app.viglide.core.indicator.IndicatorMath;
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
import app.viglide.examples.ensemble.Combiner;
import app.viglide.examples.ensemble.EnsembleParameters;
import app.viglide.examples.ensemble.EnsembleStrategy;
import app.viglide.examples.ensemble.SharpeWeightedParameters;
import java.io.IOException;
import java.math.BigDecimal;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Command-line entry point for running a single backtest.
 *
 * <p>Required args:
 *
 * <ul>
 *   <li>{@code --strategy=<name>} — registered strategy short name
 *   <li>{@code --dataset=<path>} — kline CSV (Binance epoch-ms or ISO-8601)
 *   <li>{@code --symbol=<sym>} — instrument symbol
 *   <li>{@code --interval=<ONE_MINUTE|FIVE_MINUTES|FIFTEEN_MINUTES|ONE_HOUR|FOUR_HOURS|ONE_DAY>} —
 *       candle interval (PLAN-009 Task B1)
 * </ul>
 *
 * <p>Optional args (with defaults from {@link BacktestConfig#hourlyDefaults}): {@code
 * --starting-cash}, {@code --max-position-pct}, {@code --stop-loss-pct}, {@code --take-profit-pct},
 * {@code --warmup-bars}, {@code --bars-per-year}, {@code --out}. Strategy-specific args (e.g.
 * {@code --ema-fast=11}) are forwarded to the strategy factory.
 *
 * <p>{@code --sub-bar-dataset=<path>} (PLAN-009 Task B2/B3): an optional finer-grained kline CSV
 * (e.g. 1m bars covering the same period as {@code --dataset}) used only to resolve SL/TP ordering
 * (OHLCV strategies) or re-check the liquidation guard at sub-bar granularity (fundingarb v2) —
 * never consumed by the strategy itself (D9-2). Omit for the original, decision-bar-only behaviour.
 *
 * <p>{@code --premium-index-dataset=<path>} (PLAN-015 Task C, {@code funding-model=v2} only): an
 * optional premium-index kline CSV used to build a nowcast series alongside {@code
 * --funding-dataset}'s realised settlements — see {@link app.viglide.core.domain.MarketContext}'s
 * {@code premiumIndexHistory} Javadoc. Omit for the original, realised-history-only behaviour.
 *
 * <p>{@code --min-notional=<bd> --qty-step=<bd> --price-tick=<bd>} (PLAN-010 Task D3): the
 * backtest/live-parity knob — all three or none. When set, every RM-gated evaluation carries the
 * exchange's real filters, so sizes and stop/take-profit prices are rounded exactly as the live
 * loop would round them (see {@link app.viglide.core.domain.ExchangeFilters}). Absent by default;
 * omitting all three (the default) leaves research numbers exactly as before this flag existed.
 *
 * <p>Outputs under {@code --out} (default {@code ./build/backtests/<timestamp>/}):
 *
 * <ul>
 *   <li>{@code result.json} — headline metrics and config
 *   <li>{@code trades.csv} — full trade log
 *   <li>{@code equity.csv} — per-bar equity curve
 * </ul>
 *
 * <p>Exit code 0 on success, non-zero on argument or I/O error. A single summary line is printed to
 * stdout for log scrapers.
 */
public final class BacktestCli {

  private static final BigDecimal DEFAULT_FEE_SCALE = BigDecimal.ONE;
  private static final String DEFAULT_FEE_MODE = "taker";
  private static final String DEFAULT_FUNDING_MODEL = "v2";

  private BacktestCli() {}

  public static void main(String[] argv) throws IOException {
    Map<String, String> args = Args.parse(argv);

    String strategyName = Args.require(args, "strategy");
    Path dataset = Paths.get(Args.require(args, "dataset"));
    String symbol = Args.require(args, "symbol");
    CandleInterval interval = CandleInterval.valueOf(Args.require(args, "interval"));

    BacktestConfig baseCfg = BacktestConfig.hourlyDefaults();
    BacktestConfig cfg =
        new BacktestConfig(
            Args.bigDecOpt(args, "starting-cash", baseCfg.startingCash()),
            buildFees(args),
            Args.intOpt(args, "warmup-bars", baseCfg.warmupBars()),
            Args.bigDecOpt(args, "max-position-pct", baseCfg.maxPositionPct()),
            optionalBigDec(args, "stop-loss-pct", baseCfg.stopLossPct()),
            optionalBigDec(args, "take-profit-pct", baseCfg.takeProfitPct()),
            Args.intOpt(args, "bars-per-year", BacktestConfig.barsPerYearFor(interval)),
            0,
            buildExchangeFilters(args));

    Path outDir = Paths.get(Args.opt(args, "out", defaultOutDir()));
    Files.createDirectories(outDir);

    TradingStrategy strategy =
        "ensemble".equals(strategyName)
            ? buildEnsemble(args)
            : StrategyRegistry.load().create(strategyName, args);

    Optional<RiskManagerPort> rm = buildRiskManager(args);

    // F10: route by strategy capability, not by flag presence. A single FUNDING_AWARE strategy
    // needs a funding-arb harness's delta-neutral basis-trade accounting; ensembles (which may mix
    // FUNDING_AWARE with OHLCV members) always run on the price-exposure harness, but still get
    // funding events loaded into MarketContext whenever --funding-dataset is supplied.
    boolean fundingAware =
        !"ensemble".equals(strategyName)
            && strategy.metadata().kind() == StrategyKind.FUNDING_AWARE;
    String fundingModel = Args.opt(args, "funding-model", DEFAULT_FUNDING_MODEL);
    if (!"v1".equalsIgnoreCase(fundingModel) && !"v2".equalsIgnoreCase(fundingModel)) {
      throw new IllegalArgumentException(
          "unknown --funding-model='" + fundingModel + "'; expected v1|v2");
    }
    String fundingDataset = args.get("funding-dataset");
    List<FundingEvent> funding = List.of();
    if (fundingDataset != null && !fundingDataset.isBlank()) {
      try (Stream<FundingEvent> s = CsvFundingReader.stream(Paths.get(fundingDataset))) {
        funding = s.toList();
      }
    }

    // PLAN-015 Task C: premium-index nowcast series, distinct from realised `funding` above (see
    // MarketContext.premiumIndexHistory's Javadoc). Absent by default -- omitting it leaves every
    // pre-existing run byte-identical. Only the two-leg v2 funding-arb harness consumes it, so
    // reject the flag anywhere else rather than parsing a dense CSV and silently dropping it --
    // a mistyped --funding-model must not degrade into an ignored flag (CLAUDE.md §5).
    String premiumIndexDataset = args.get("premium-index-dataset");
    boolean fundingArbV2 = fundingAware && "v2".equalsIgnoreCase(fundingModel);
    if (premiumIndexDataset != null && !premiumIndexDataset.isBlank() && !fundingArbV2) {
      throw new IllegalArgumentException(
          "--premium-index-dataset requires a FUNDING_AWARE strategy with --funding-model=v2;"
              + " got strategy='"
              + strategyName
              + "' fundingModel='"
              + fundingModel
              + "'");
    }

    // Materialised once (not just streamed) so the buy-and-hold benchmark (C.5) can walk the same
    // candles the strategy saw, after whichever harness has consumed them.
    List<Candle> candles;
    try (Stream<Candle> s = CsvKlineReader.stream(dataset)) {
      candles = s.toList();
    }

    // PLAN-009 Task B2/B3: optional finer-grained series for sub-bar SL/TP (OHLCV) or liquidation-
    // guard (fundingarb v2) resolution. Empty when not supplied, preserving exact prior behaviour.
    String subBarDataset = args.get("sub-bar-dataset");
    List<Candle> subBarCandles = List.of();
    if (subBarDataset != null && !subBarDataset.isBlank()) {
      try (Stream<Candle> s = CsvKlineReader.stream(Paths.get(subBarDataset))) {
        subBarCandles = s.toList();
      }
    }

    BacktestResult result;
    if (fundingArbV2) {
      // PLAN-008 Task F: the two-leg model needs the spot leg's own price series.
      List<Candle> spotCandles;
      try (Stream<Candle> s =
          CsvKlineReader.stream(Paths.get(Args.require(args, "spot-dataset")))) {
        spotCandles = s.toList();
      }
      // Read only on the branch that consumes it -- this series is dense (a year of 1m samples
      // is ~525k rows), so parsing it on a path that discards it is pure waste.
      List<PremiumIndexEvent> premiumIndex = List.of();
      if (premiumIndexDataset != null && !premiumIndexDataset.isBlank()) {
        try (Stream<PremiumIndexEvent> s =
            CsvPremiumIndexReader.stream(Paths.get(premiumIndexDataset))) {
          premiumIndex = s.toList();
        }
      }
      result =
          FundingArbHarnessV2.run(
              strategy,
              candles,
              spotCandles,
              funding,
              premiumIndex,
              symbol,
              interval,
              cfg,
              rm,
              subBarCandles);
    } else if (fundingAware) {
      result = FundingArbHarness.run(strategy, candles, funding, symbol, interval, cfg, rm);
    } else {
      result =
          BacktestHarness.run(
              strategy, candles.stream(), symbol, interval, cfg, funding, rm, subBarCandles);
    }

    writeOutputs(outDir, result, cfg, strategyName, symbol, interval, dataset, args, candles);
    System.out.println(summaryLine(result, outDir));
  }

  // ── output writers ───────────────────────────────────────────────────────────────────────────

  private static void writeOutputs(
      Path outDir,
      BacktestResult result,
      BacktestConfig cfg,
      String strategyName,
      String symbol,
      CandleInterval interval,
      Path dataset,
      Map<String, String> args,
      List<Candle> candles)
      throws IOException {

    SharpeStats stats = Metrics.sharpeStats(result.equityCurve());
    BuyHoldBenchmark buyHold = buyHoldBenchmark(candles, cfg);

    Map<String, Object> resultJson = new LinkedHashMap<>();
    resultJson.put("strategy", strategyName);
    resultJson.put("symbol", symbol);
    resultJson.put("interval", interval.name());
    resultJson.put("dataset", dataset.toString());
    resultJson.put("startingEquity", result.startingEquity());
    resultJson.put("endingEquity", result.endingEquity());
    resultJson.put("totalReturn", result.totalReturn());
    resultJson.put("sharpe", result.sharpe());
    resultJson.put("maxDrawdown", result.maxDrawdown());
    resultJson.put("winRate", result.winRate());
    resultJson.put("tradeCount", result.tradeCount());
    resultJson.put("refusals", result.refusals().size()); // F6: refusal count alongside tradeCount.
    // C.2/C.5: PSR and the buy-and-hold benchmark ride alongside every OOS report from now on, so
    // "beat holding the coin" is never a hand-wave.
    Metrics.PsrResult psr = Metrics.probabilisticSharpeRatioDetailed(stats, 0.0);
    resultJson.put("psr", psr.probability());
    // PLAN-013 Task B (finding F14): the clamp was silent before this -- a clamped cell's psr is
    // still a finite probability (that's the point of the clamp), but a reader had no way to tell
    // it apart from a normally-computed one without this flag.
    resultJson.put("psrRadicandClamped", psr.radicandClamped());
    resultJson.put("dailyObservations", stats.observations());
    resultJson.put("buyHoldSharpe", buyHold.sharpe());
    resultJson.put("buyHoldReturn", buyHold.totalReturn());
    // PLAN-013 Task A (finding F2): reported alongside sharpe/psr, never in place of them -- see
    // docs/runbook.md §6 for why sharpe alone misleads on a sparse-trading strategy, and why
    // deployedOnlySharpe is not the "stricter" statistic its name suggests.
    resultJson.put("returnOnDeployedCapital", EconomicMetrics.returnOnDeployedCapital(result));
    resultJson.put("deploymentRatio", EconomicMetrics.deploymentRatio(result));
    resultJson.put(
        "deployedOnlySharpe",
        EconomicMetrics.deployedOnlySharpe(result.equityCurve(), result.trades()));
    resultJson.put("ulcerIndex", EconomicMetrics.ulcerIndex(result.equityCurve()));
    resultJson.put("calmar", EconomicMetrics.calmar(result));
    // E: every result records which cost assumption produced it — never a silent default.
    resultJson.put("feeScale", Args.bigDecOpt(args, "fee-scale", DEFAULT_FEE_SCALE));
    resultJson.put("feeMode", Args.opt(args, "fee-mode", DEFAULT_FEE_MODE));
    // PLAN-009 Task B2/B3: every result records whether sub-bar execution realism was on, so a
    // matrix note never has to guess which mode produced a given cell.
    resultJson.put("subBarResolution", args.get("sub-bar-dataset") != null);
    // PLAN-010 Task D3: every result records whether the exchange-filters parity knob was on, so a
    // reader never has to guess whether sizes/stops in this run were exchange-legal-rounded.
    resultJson.put("exchangeFiltersApplied", cfg.exchangeFilters().isPresent());
    resultJson.put("config", configToMap(cfg));
    resultJson.put("strategyArgs", stripCommonArgs(args));
    resultJson.put("diagnostics", numericMap(result.diagnostics()));

    Files.writeString(outDir.resolve("result.json"), JsonWriter.pretty(resultJson));
    Files.writeString(outDir.resolve("trades.csv"), tradesCsv(result.trades()));
    Files.writeString(outDir.resolve("equity.csv"), equityCsv(result.equityCurve()));
    Files.writeString(outDir.resolve("refusals.csv"), refusalsCsv(result.refusals()));
  }

  /**
   * Buy-and-hold benchmark over the same evaluated window as the strategy (PLAN-008 Task C.5):
   * starts at the first post-warmup bar's close (the earliest bar the strategy could have acted on)
   * and tracks {@code startingCash × close_t / close_first} through the end of the dataset. Empty
   * (all-zero) when there are fewer candles than {@code warmupBars}.
   */
  private record BuyHoldBenchmark(double sharpe, BigDecimal totalReturn) {
    private static final BuyHoldBenchmark EMPTY = new BuyHoldBenchmark(0.0, BigDecimal.ZERO);
  }

  private static BuyHoldBenchmark buyHoldBenchmark(List<Candle> candles, BacktestConfig cfg) {
    int startIdx = cfg.warmupBars() - 1;
    if (startIdx < 0 || startIdx >= candles.size()) {
      return BuyHoldBenchmark.EMPTY;
    }
    BigDecimal closeFirst = candles.get(startIdx).close();
    if (closeFirst.signum() <= 0) {
      return BuyHoldBenchmark.EMPTY;
    }
    List<EquityPoint> curve = new ArrayList<>(candles.size() - startIdx);
    for (int i = startIdx; i < candles.size(); i++) {
      Candle c = candles.get(i);
      BigDecimal equity =
          cfg.startingCash()
              .multiply(c.close(), IndicatorMath.MC)
              .divide(closeFirst, IndicatorMath.MC);
      curve.add(new EquityPoint(c.openTime(), equity));
    }
    double sharpe = Metrics.annualisedSharpe(curve);
    BigDecimal endEquity = curve.get(curve.size() - 1).equity();
    BigDecimal totalReturn =
        endEquity.subtract(cfg.startingCash()).divide(cfg.startingCash(), IndicatorMath.MC);
    return new BuyHoldBenchmark(sharpe, totalReturn);
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

  /** F6: makes every Risk Manager refusal — not just executed trades — auditable after the fact. */
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

  /** Wraps a field in double quotes (escaping embedded quotes) if it contains a comma. */
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
    m.put("maxPositionPct", cfg.maxPositionPct());
    m.put("stopLossPct", cfg.stopLossPct());
    m.put("takeProfitPct", cfg.takeProfitPct());
    m.put("barsPerYear", cfg.barsPerYear());
    Map<String, Object> fees = new LinkedHashMap<>();
    fees.put("makerBps", cfg.fees().makerBps());
    fees.put("takerBps", cfg.fees().takerBps());
    fees.put("slippageBps", cfg.fees().slippageBps());
    m.put("fees", fees);
    cfg.exchangeFilters()
        .ifPresent(
            f -> {
              Map<String, Object> filters = new LinkedHashMap<>();
              filters.put("minNotional", f.minNotional());
              filters.put("qtyStepSize", f.qtyStepSize());
              filters.put("priceTickSize", f.priceTickSize());
              m.put("exchangeFilters", filters);
            });
    return m;
  }

  /** Removes args that are already represented elsewhere, leaving just strategy-specific knobs. */
  private static Map<String, Object> stripCommonArgs(Map<String, String> args) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : args.entrySet()) {
      switch (e.getKey()) {
        case "strategy",
            "dataset",
            "symbol",
            "interval",
            "out",
            "starting-cash",
            "max-position-pct",
            "stop-loss-pct",
            "take-profit-pct",
            "warmup-bars",
            "bars-per-year",
            "fee-scale",
            "fee-mode",
            "funding-model",
            "spot-dataset",
            "min-notional",
            "qty-step",
            "price-tick" -> {}
        default -> out.put(e.getKey(), e.getValue());
      }
    }
    return out;
  }

  /** Diagnostics map carries Long values from the harness — pass them through as numbers. */
  private static Map<String, Object> numericMap(Map<String, Object> in) {
    return new LinkedHashMap<>(in);
  }

  private static String summaryLine(BacktestResult r, Path outDir) {
    return String.format(
        "backtest: trades=%d totalReturn=%s sharpe=%.3f maxDrawdown=%s winRate=%.3f%s → %s",
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

  private static BigDecimal optionalBigDec(
      Map<String, String> args, String key, BigDecimal fallback) {
    String v = args.get(key);
    if (v == null) return fallback;
    if (v.isBlank() || v.equalsIgnoreCase("none") || v.equalsIgnoreCase("null")) return null;
    return new BigDecimal(v);
  }

  private static String defaultOutDir() {
    String ts =
        LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    return "build/backtests/" + ts;
  }

  // ── fee model ────────────────────────────────────────────────────────────────────────────────

  /**
   * Builds the fee model from CLI args (PLAN-008 Task E): {@code --fee-mode=taker|maker} (default
   * {@code taker}) picks the base cost assumption, then {@code --fee-scale=<double>} (default
   * {@code 1.0}) multiplies every component — {@code 0} for a frictionless sanity check, {@code 2}
   * for a pessimistic sensitivity band.
   */
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
    return base.scaled(feeScale, IndicatorMath.MC);
  }

  // ── exchange filters (backtest/live-parity knob, PLAN-010 Task D3) ─────────────────────────────

  /**
   * Builds the optional exchange-filters parity knob from {@code --min-notional}, {@code
   * --qty-step}, {@code --price-tick}. All three are optional but all-or-nothing: setting one
   * without the others is a configuration mistake, not a partial filter (CLAUDE.md §5 — fail loudly
   * rather than silently apply an incomplete rounding rule). Absent when none are set, so every
   * existing invocation's research numbers are unchanged.
   */
  private static Optional<ExchangeFilters> buildExchangeFilters(Map<String, String> args) {
    boolean any =
        args.containsKey("min-notional")
            || args.containsKey("qty-step")
            || args.containsKey("price-tick");
    if (!any) {
      return Optional.empty();
    }
    if (!args.containsKey("min-notional")
        || !args.containsKey("qty-step")
        || !args.containsKey("price-tick")) {
      throw new IllegalArgumentException(
          "--min-notional/--qty-step/--price-tick must be set together or not at all (PLAN-010"
              + " Task D3) — got a partial set");
    }
    return Optional.of(
        new ExchangeFilters(
            new BigDecimal(args.get("min-notional")),
            new BigDecimal(args.get("qty-step")),
            new BigDecimal(args.get("price-tick"))));
  }

  // ── risk manager ────────────────────────────────────────────────────────────────────────────

  /**
   * Builds the optional Risk Manager from CLI args. {@code --risk=on} (default) activates RM gating
   * with default parameters; {@code --risk=off} disables it for raw ungated backtests.
   *
   * <p>F7: the returned port is wrapped in {@link BacktestClockSync} so the Risk Manager's
   * staleness check tracks simulated bar time instead of wall-clock time — otherwise every
   * historical signal would be older than {@code maxStaleInputAge} and get refused.
   */
  private static Optional<RiskManagerPort> buildRiskManager(Map<String, String> args) {
    String risk = Args.opt(args, "risk", "on");
    if ("off".equalsIgnoreCase(risk)) return Optional.empty();
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
    return Optional.of(new BacktestClockSync(rm, clock));
  }

  // ── ensemble construction ────────────────────────────────────────────────────────────────────

  /**
   * Builds an {@link EnsembleStrategy} from CLI args. Recognised flags:
   *
   * <ul>
   *   <li>{@code --strategies=emarsi,meanrev,macdtrend} — required, comma-separated underlying
   *       strategy names.
   *   <li>{@code --ensemble-from-winners=<template>} — optional. The template must contain the
   *       literal {@code <strategy>} placeholder; each substitution becomes a key into {@code
   *       winners.json} (path overridable via {@code --winners}). Example: {@code
   *       BTCUSDT_<strategy>_2025}. When absent, each underlying strategy is built with textbook
   *       defaults.
   *   <li>{@code --combiner=naive|sharpe-weighted} — default {@code naive}. When {@code
   *       sharpe-weighted}, {@code --ensemble-from-winners} is required and {@code cvSharpeMedian}
   *       (falling back to the legacy {@code oosSharpeMedian} — PLAN-008 Task D.2) is read from
   *       each winners.json entry to build the vote weights.
   *   <li>{@code --concentration} — α exponent for sharpe-weighted mode (default 1.0).
   * </ul>
   */
  private static TradingStrategy buildEnsemble(Map<String, String> args) throws IOException {
    String[] names = Args.require(args, "strategies").split(",");
    String template = args.get("ensemble-from-winners");
    Map<String, Object> winners =
        (template == null || template.isBlank())
            ? Map.of()
            : loadWinners(Paths.get(Args.opt(args, "winners", "winners.json")));

    // Build underlying strategies, tracking the CLI short name alongside each instance.
    List<String> cliNames = new ArrayList<>(names.length);
    List<TradingStrategy> underlying = new ArrayList<>(names.length);
    StrategyRegistry registry = StrategyRegistry.load();
    for (String raw : names) {
      String name = raw.trim();
      if (name.isEmpty()) continue;
      Map<String, String> strategyArgs;
      if (template == null || template.isBlank()) {
        strategyArgs = Map.of();
      } else {
        String key = template.replace("<strategy>", name);
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) winners.get(key);
        if (entry == null) {
          throw new IllegalArgumentException(
              "no winners.json entry for '" + key + "' (template: " + template + ")");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) entry.get("params");
        strategyArgs = CliArgs.camelMapToCliArgs(params);
      }
      cliNames.add(name);
      underlying.add(registry.create(name, strategyArgs));
    }

    String combinerName = Args.opt(args, "combiner", "naive");
    Combiner combiner =
        switch (combinerName) {
          case "naive" -> {
            EnsembleParameters cfg =
                new EnsembleParameters(
                    Args.doubleOpt(args, "ensemble-min-confidence", 0.5),
                    Args.doubleOpt(args, "ensemble-max-confidence-sum", 2.5));
            yield Combiner.naive(cfg);
          }
          case "sharpe-weighted" -> {
            if (template == null || template.isBlank()) {
              throw new IllegalArgumentException(
                  "--combiner=sharpe-weighted requires --ensemble-from-winners to load Sharpe"
                      + " weights");
            }
            // Map from each strategy's metadata name (used inside SharpeWeightedCombiner) to its
            // calibration cross-validation Sharpe (PLAN-008 D.2: cv* preferred, oos* legacy
            // fallback — winners.json entries promoted before the rename only have the latter).
            Map<String, Double> sharpeByStrategy = new LinkedHashMap<>();
            for (int i = 0; i < underlying.size(); i++) {
              String cliName = cliNames.get(i);
              String metaName = underlying.get(i).metadata().name();
              String key = template.replace("<strategy>", cliName);
              @SuppressWarnings("unchecked")
              Map<String, Object> entry = (Map<String, Object>) winners.get(key);
              if (entry == null) {
                throw new IllegalArgumentException(
                    "no winners.json entry for '" + key + "' when loading Sharpe weights");
              }
              Object sharpeVal = Args.jsonFallback(entry, "cvSharpeMedian", "oosSharpeMedian");
              if (sharpeVal == null) {
                throw new IllegalArgumentException(
                    "cvSharpeMedian (nor legacy oosSharpeMedian) missing in winners.json entry '"
                        + key
                        + "'");
              }
              sharpeByStrategy.put(metaName, ((Number) sharpeVal).doubleValue());
            }
            SharpeWeightedParameters swParams =
                new SharpeWeightedParameters(
                    sharpeByStrategy,
                    Args.doubleOpt(args, "concentration", 1.0),
                    Args.doubleOpt(args, "ensemble-min-confidence", 0.5),
                    Args.doubleOpt(args, "ensemble-max-confidence-sum", 1.0));
            yield Combiner.sharpeWeighted(swParams);
          }
          default ->
              throw new IllegalArgumentException(
                  "unknown --combiner value: '"
                      + combinerName
                      + "'; expected naive|sharpe-weighted");
        };

    return new EnsembleStrategy(underlying, combiner);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadWinners(Path path) throws IOException {
    if (!Files.exists(path)) {
      throw new IllegalArgumentException("winners file not found: " + path);
    }
    Object root = JsonReader.parse(Files.readString(path));
    if (!(root instanceof Map<?, ?> m)) {
      throw new IllegalArgumentException("winners file is not a JSON object: " + path);
    }
    return (Map<String, Object>) m;
  }
}
