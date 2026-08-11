package app.viglide.research.cli;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.BacktestResult;
import app.viglide.core.data.CsvFundingReader;
import app.viglide.core.data.CsvKlineReader;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.params.JsonWriter;
import app.viglide.core.spi.StrategyRegistry;
import app.viglide.core.spi.TradingStrategy;
import app.viglide.research.calibrate.FoldRunner;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Null-model (permutation) baseline (PLAN-013 Task I, review finding F15): "how often does this
 * pipeline produce a result this good from data with no real edge in it?" — the cheapest, most
 * convincing honesty artifact available, and a direct complement to K1′ (ADR-0016 condition 6).
 *
 * <p>Re-runs the identical strategy/params/candles {@code N} times against {@code
 * --funding-dataset} <strong>circularly permuted</strong> — the rate <em>sequence</em> keeps its
 * own autocorrelation structure and marginal distribution (still looks like real funding data
 * statistically), but its alignment with price/time is destroyed by rotating it to a random offset.
 * Circular permutation, deliberately not i.i.d. shuffling — shuffling would destroy the
 * autocorrelation real funding data has, making the null unrealistically easy to beat and inflating
 * the reported percentile.
 *
 * <p><strong>Runs the same harness the fit ran</strong> — {@code --funding-model=v1|v2}, defaulting
 * to v2, exactly as {@code CalibrateCli} does. PLAN-016 Task C found this CLI hardwired to v1 (the
 * income-only model: no spot leg, no basis P&amp;L, no liquidation guard) while every calibration
 * it baselines runs v2, which would have made the permutation test a comparison against a model
 * nobody fitted. {@code --fee-mode}/{@code --fee-scale} are honoured for the same reason: a 2x fee
 * sweep that cannot be null-tested is not a sweep the gate can read.
 *
 * <p>Single-symbol by construction: it permutes one funding series and cannot express "a random
 * basket of the same universe". For a cross-sectional strategy use {@code PortfolioNullModelCli} —
 * two null hypotheses, two CLIs, rather than one bent over both.
 *
 * <p>Invoke as: {@code ./gradlew :viglide-strategies:nullModel --args='--strategy=fundingarb
 * --dataset=<perp.csv> --funding-dataset=<funding.csv> --spot-dataset=<spot.csv> --symbol=ETHUSDT
 * --interval=ONE_HOUR --null-model=200 --seed=42'} — see {@code docs/runbook/} §5.
 */
public final class NullModelCli {

  private NullModelCli() {}

  public static void main(String[] args) throws IOException {
    run(args, System.out);
  }

  // Public (not package-private): the fundingarb-specific end-to-end tests (viglide-strategies,
  // private repo, PLAN-018 R-5) call this directly to capture stdout/report output without going
  // through main()'s System.out binding.
  public static void run(String[] rawArgs, PrintStream out) throws IOException {
    Map<String, String> args = Args.parse(rawArgs);
    String strategyName = Args.require(args, "strategy");
    Path dataset = Paths.get(Args.require(args, "dataset"));
    Path fundingDataset = Paths.get(Args.require(args, "funding-dataset"));
    String symbol = Args.require(args, "symbol");
    CandleInterval interval = CandleInterval.valueOf(Args.require(args, "interval"));
    int n = Args.intOpt(args, "null-model", 200);
    long seed = Args.longOpt(args, "seed", 42L);

    List<Candle> candles;
    try (var s = CsvKlineReader.stream(dataset)) {
      candles = s.toList();
    }
    List<FundingEvent> realFunding;
    try (var s = CsvFundingReader.stream(fundingDataset)) {
      realFunding = s.toList();
    }

    // PLAN-016 Task C: --funding-model=v1|v2 with the same meaning and the same default as
    // CalibrateCli's. Before this the CLI was hardwired to FoldRunner.withFunding -- the v1
    // income-only model with no spot leg, no basis P&L and no liquidation guard -- while every
    // calibration it is supposed to be the null model *for* runs v2. A permutation test against a
    // different harness than the fit answers a question nobody asked, so the flag is required here
    // rather than defaulted quietly: v2 needs --spot-dataset, and failing loudly on a missing spot
    // series is better than silently measuring the wrong model.
    String fundingModel = Args.opt(args, "funding-model", "v2");
    List<Candle> spotCandles = List.of();
    if ("v2".equalsIgnoreCase(fundingModel)) {
      try (var s = CsvKlineReader.stream(Paths.get(Args.require(args, "spot-dataset")))) {
        spotCandles = s.toList();
      }
    } else if (!"v1".equalsIgnoreCase(fundingModel)) {
      throw new IllegalArgumentException(
          "unknown --funding-model='" + fundingModel + "'; expected v1|v2");
    }
    List<Candle> spot = spotCandles;
    java.util.function.Function<List<FundingEvent>, FoldRunner> runnerFor =
        events ->
            "v2".equalsIgnoreCase(fundingModel)
                ? FoldRunner.withFundingV2(events, spot)
                : FoldRunner.withFunding(events);

    TradingStrategy strategy = StrategyRegistry.load().create(strategyName, args);
    BacktestConfig cfg =
        new BacktestConfig(
            Args.bigDecOpt(args, "starting-cash", BigDecimal.valueOf(10_000)),
            // Honours --fee-mode/--fee-scale like every other research CLI. Previously hardcoded to
            // binanceDefault(), so a 2x fee sweep could not be null-tested at all.
            PortfolioCli.buildFees(args),
            Args.intOpt(args, "warmup-bars", 100),
            BigDecimal.valueOf(0.02),
            BigDecimal.valueOf(0.02),
            BigDecimal.valueOf(0.04),
            8760);
    FoldRunner runner = runnerFor.apply(realFunding);

    Instant start = Instant.now();
    BacktestResult realResult = runner.run(strategy, candles, symbol, interval, cfg);
    double realStatistic = realResult.totalReturn().doubleValue();

    Random rng = new Random(seed);
    double[] nullStatistics = new double[n];
    for (int i = 0; i < n; i++) {
      List<FundingEvent> permuted =
          circularPermute(realFunding, rng.nextInt(Math.max(1, realFunding.size())));
      BacktestResult nullResult =
          runnerFor.apply(permuted).run(strategy, candles, symbol, interval, cfg);
      nullStatistics[i] = nullResult.totalReturn().doubleValue();
    }
    long elapsedMs = java.time.Duration.between(start, Instant.now()).toMillis();

    PercentileResult pct = percentileOf(nullStatistics, realStatistic);

    out.println("null-model: strategy=" + strategyName + " symbol=" + symbol + " N=" + n);
    out.println(
        String.format(
            "real totalReturn=%.6f (tradeCount=%d) -- percentile within null distribution: %.1f%%"
                + " (%d strictly below, %d tied, of %d permuted runs)",
            realStatistic,
            realResult.tradeCount(),
            pct.percentile() * 100,
            pct.countStrictlyBelow(),
            pct.countEqual(),
            n));
    out.println(
        String.format(
            "runtime: %d ms total, %.1f ms/permutation (budget PLAN-016's full sweep from this)",
            elapsedMs, n == 0 ? 0.0 : (double) elapsedMs / n));
    out.println(asciiHistogram(nullStatistics, realStatistic));

    String outPath = Args.opt(args, "out", null);
    if (outPath != null) {
      writeReport(
          Paths.get(outPath),
          strategyName,
          symbol,
          n,
          seed,
          realStatistic,
          realResult.tradeCount(),
          pct,
          elapsedMs,
          nullStatistics);
      out.println("report written to " + outPath);
    }
  }

  /**
   * A real statistic's rank within its null distribution: how many null draws it strictly beats,
   * how many it ties, and the resulting percentile.
   */
  record PercentileResult(int countStrictlyBelow, int countEqual, double percentile) {}

  /**
   * Mid-rank tie correction (standard for permutation tests, not a bespoke choice): a real result
   * exactly tied with every null — e.g. a strategy so broken it never trades regardless of the
   * funding data, real and null {@code totalReturn} both always 0 — must land at the 50th
   * percentile ("indistinguishable from the null"), not the 100th a naive {@code
   * countBelowOrEqual/n} would report just because {@code <=} is trivially true against an
   * all-equal distribution.
   */
  static PercentileResult percentileOf(double[] nullStatistics, double realStatistic) {
    int countStrictlyBelow = 0;
    int countEqual = 0;
    for (double v : nullStatistics) {
      if (v < realStatistic) {
        countStrictlyBelow++;
      } else if (v == realStatistic) {
        countEqual++;
      }
    }
    int n = nullStatistics.length;
    double percentile = n == 0 ? 0.5 : (countStrictlyBelow + 0.5 * countEqual) / n;
    return new PercentileResult(countStrictlyBelow, countEqual, percentile);
  }

  /**
   * Rotates the rate sequence by {@code offset} positions (wrap-around), keeping the original
   * timestamps fixed — preserves the rate sequence's own autocorrelation and marginal distribution
   * while destroying its alignment with price/time.
   */
  static List<FundingEvent> circularPermute(List<FundingEvent> original, int offset) {
    int n = original.size();
    if (n == 0) {
      return List.of();
    }
    List<FundingEvent> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      BigDecimal rotatedRate = original.get(Math.floorMod(i + offset, n)).rate();
      out.add(new FundingEvent(original.get(i).time(), rotatedRate));
    }
    return out;
  }

  /**
   * ASCII histogram — no binary images (CLAUDE.md §5a). 20 buckets, real result marked with `*`.
   */
  static String asciiHistogram(double[] nullStatistics, double realStatistic) {
    if (nullStatistics.length == 0) {
      return "(no null samples)";
    }
    double min = Double.MAX_VALUE;
    double max = -Double.MAX_VALUE;
    for (double v : nullStatistics) {
      min = Math.min(min, v);
      max = Math.max(max, v);
    }
    min = Math.min(min, realStatistic);
    max = Math.max(max, realStatistic);
    int buckets = 20;
    double width = (max - min) <= 0 ? 1.0 : (max - min) / buckets;
    int[] counts = new int[buckets];
    for (double v : nullStatistics) {
      int b = width == 0 ? 0 : Math.min(buckets - 1, (int) ((v - min) / width));
      counts[Math.max(0, b)]++;
    }
    int realBucket = width == 0 ? 0 : Math.min(buckets - 1, (int) ((realStatistic - min) / width));
    int maxCount = 1;
    for (int c : counts) {
      maxCount = Math.max(maxCount, c);
    }
    StringBuilder sb =
        new StringBuilder("null distribution (totalReturn), * marks the real result:\n");
    for (int b = 0; b < buckets; b++) {
      double bucketStart = min + b * width;
      int barLen = (int) Math.round(40.0 * counts[b] / maxCount);
      sb.append(String.format("%+9.4f | ", bucketStart))
          .append("#".repeat(barLen))
          .append(b == realBucket ? " *" : "")
          .append(String.format("  (%d)%n", counts[b]));
    }
    return sb.toString();
  }

  private static void writeReport(
      Path out,
      String strategyName,
      String symbol,
      int n,
      long seed,
      double realStatistic,
      int realTradeCount,
      PercentileResult pct,
      long elapsedMs,
      double[] nullStatistics)
      throws IOException {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("strategy", strategyName);
    report.put("symbol", symbol);
    report.put("nullSamples", n);
    report.put("seed", seed);
    report.put("realTotalReturn", realStatistic);
    report.put("realTradeCount", realTradeCount);
    report.put("percentile", pct.percentile());
    report.put("countStrictlyBelow", pct.countStrictlyBelow());
    report.put("countEqual", pct.countEqual());
    report.put("runtimeMs", elapsedMs);
    report.put("asciiHistogram", asciiHistogram(nullStatistics, realStatistic));
    if (out.getParent() != null) {
      Files.createDirectories(out.getParent());
    }
    Files.writeString(out, JsonWriter.pretty(report));
  }
}
