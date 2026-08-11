package app.viglide.research.cli;

import app.viglide.core.backtest.EconomicMetrics;
import app.viglide.core.backtest.FeeModel;
import app.viglide.core.backtest.Metrics;
import app.viglide.core.data.CsvKlineReader;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.params.JsonWriter;
import app.viglide.research.benchmark.BuyAndHoldBenchmark;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs {@link BuyAndHoldBenchmark} over a pair universe and reports it on the same metrics a
 * strategy run reports — total return, max drawdown, ulcer index, annualised Sharpe, fees paid.
 *
 * <p>Exists because ADR-0016's verdict needs "did it beat owning the basket?" answered on
 * comparable terms, and until now the only buy-and-hold in either repository was a private helper
 * in a disabled JUnit spike that returned a single number.
 *
 * <p>Reads <strong>spot</strong> klines ({@code <PAIR>_spot_<tag>_<label>.csv}), not perp: the
 * benchmark is owning the asset, and a perp series would fold basis into a comparison that is
 * supposed to be the simplest possible alternative.
 *
 * <p>Invoke as: {@code ./gradlew :viglide-strategies:buyAndHold --args='--pairs=BTCUSDT,ETHUSDT
 * --label=full --datasets-dir=data/merged --interval=ONE_HOUR --fee-mode=taker
 * --out=build/backtests/buyhold'}
 */
public final class BuyAndHoldCli {

  private BuyAndHoldCli() {}

  public static void main(String[] argv) throws IOException {
    System.exit(run(argv));
  }

  public static int run(String[] argv) throws IOException {
    Map<String, String> args = Args.parse(argv);

    List<String> pairs = List.of(Args.require(args, "pairs").split(","));
    String label = Args.require(args, "label");
    Path datasetsDir = Paths.get(Args.opt(args, "datasets-dir", "data"));
    CandleInterval interval = CandleInterval.valueOf(Args.opt(args, "interval", "ONE_HOUR"));
    String intervalTag = PortfolioCli.INTERVAL_TAG.get(interval);
    BigDecimal startingCash = Args.bigDecOpt(args, "starting-cash", new BigDecimal("10000"));
    FeeModel fees = PortfolioCli.buildFees(args);

    Map<String, List<Candle>> spotBySymbol = new LinkedHashMap<>();
    List<String> included = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (String pair : pairs) {
      Path spotPath = datasetsDir.resolve(pair + "_spot_" + intervalTag + "_" + label + ".csv");
      if (!Files.exists(spotPath)) {
        System.out.println("[SKIP] missing spot dataset for " + pair + " (" + spotPath + ")");
        skipped.add(pair);
        continue;
      }
      try (var s = CsvKlineReader.stream(spotPath)) {
        spotBySymbol.put(pair, s.toList());
      }
      included.add(pair);
    }
    if (included.isEmpty()) {
      System.err.println("no spot datasets found — nothing to benchmark");
      return 2;
    }

    var result = BuyAndHoldBenchmark.run(spotBySymbol, startingCash, fees);

    Map<String, Object> json = new LinkedHashMap<>();
    json.put("benchmark", "equal-weight-spot-buy-and-hold");
    json.put("pairsIncluded", included);
    json.put("pairsSkipped", skipped);
    json.put("interval", interval.name());
    json.put("label", label);
    json.put("startingCash", startingCash);
    json.put("feeMode", Args.opt(args, "fee-mode", "taker"));
    json.put("feeScale", Args.bigDecOpt(args, "fee-scale", BigDecimal.ONE));
    json.put("totalReturn", result.totalReturn());
    json.put("feesPaid", result.feesPaid());
    json.put("symbolsHeld", result.symbolsHeld());
    json.put("maxDrawdown", Metrics.maxDrawdown(result.equityCurve()));
    json.put("ulcerIndex", EconomicMetrics.ulcerIndex(result.equityCurve()));
    json.put("annualisedSharpe", Metrics.annualisedSharpe(result.equityCurve()));
    json.put("equityPoints", result.equityCurve().size());
    json.put("returnBySymbol", new TreeMap<>(result.returnBySymbol()));

    System.out.printf(
        "buy-and-hold: pairs=%d totalReturn=%s maxDD=%s ulcer=%.3f fees=%s%n",
        result.symbolsHeld(),
        result.totalReturn().setScale(4, java.math.RoundingMode.HALF_UP).toPlainString(),
        Metrics.maxDrawdown(result.equityCurve())
            .setScale(4, java.math.RoundingMode.HALF_UP)
            .toPlainString(),
        EconomicMetrics.ulcerIndex(result.equityCurve()),
        result.feesPaid().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());

    String outArg = args.get("out");
    if (outArg != null && !outArg.isBlank()) {
      Path outDir = Paths.get(outArg);
      Files.createDirectories(outDir);
      Files.writeString(outDir.resolve("buy-and-hold.json"), JsonWriter.pretty(json));
      System.out.println("wrote " + outDir.resolve("buy-and-hold.json"));
    }
    return 0;
  }
}
