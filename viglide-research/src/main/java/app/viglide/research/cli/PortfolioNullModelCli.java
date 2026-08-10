package app.viglide.research.cli;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.BacktestResult;
import app.viglide.core.backtest.FeeModel;
import app.viglide.core.backtest.PortfolioBacktestHarness;
import app.viglide.core.calibrate.PortfolioCandidate;
import app.viglide.core.data.CsvFundingReader;
import app.viglide.core.data.CsvKlineReader;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.params.JsonWriter;
import app.viglide.core.risk.BacktestClockSync;
import app.viglide.core.risk.MutableClock;
import app.viglide.core.risk.RiskManager;
import app.viglide.core.risk.RiskManagerPort;
import app.viglide.core.risk.RiskParameters;
import app.viglide.core.spi.PortfolioParameterSpaceRegistry;
import app.viglide.research.nullmodel.CrossSectionalNullModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ADR-0016 condition 6 for a cross-sectional strategy (PLAN-023 Task B): runs the real book once,
 * then {@code --n} matched-turnover random-top-k baselines over the same data, and reports where
 * the real result falls in their distribution.
 *
 * <p>{@link NullModelCli} cannot do this: it takes a single {@code --symbol} and permutes that
 * symbol's funding series, which is the right null model for {@code fundingarb} and structurally
 * unable to express "a random basket of the same eligible universe". The two coexist rather than
 * one bending to cover both, because the null hypotheses genuinely differ.
 *
 * <p>Required: {@code --pairs}, {@code --strategy}, {@code --label}, {@code --k}, {@code --params}.
 * Optional: {@code --min-funding-events}, {@code --n} (default 200, ADR-0016's pre-registered
 * value), {@code --seed}, {@code --interval}, {@code --datasets-dir}, {@code --starting-cash},
 * {@code --fee-mode}, {@code --fee-scale}, {@code --rm-*}, {@code --warmup-bars}, {@code --out}.
 *
 * <p><strong>The real book and the permutations are run through the same path</strong> — one {@code
 * runTargets} call for the strategy, {@code n} through the two-leg engine for the baselines, same
 * fees, same Risk Manager construction. A baseline measured differently from the thing it is a
 * baseline for is not a baseline.
 *
 * <p>{@code --params} names the exact candidate to compare (e.g. {@code
 * k=2,windowSize=6,minFundingEvents=10}) and <strong>must select exactly one</strong> member of the
 * strategy's grid. Under-specifying it is rejected rather than resolved to the first match:
 * silently picking an arbitrary member of a matching set as "the" result would make the comparison
 * unreproducible from the recorded command. {@code --k} and {@code --min-funding-events} separately
 * describe the <em>baseline's</em> book so the turnover match is explicit rather than inferred.
 */
public final class PortfolioNullModelCli {

  private PortfolioNullModelCli() {}

  public static void main(String[] argv) throws IOException {
    System.exit(run(argv));
  }

  public static int run(String[] argv) throws IOException {
    Map<String, String> args = Args.parse(argv);

    List<String> pairs = List.of(Args.require(args, "pairs").split(","));
    String strategyName = Args.require(args, "strategy");
    String label = Args.require(args, "label");
    int k = Integer.parseInt(Args.require(args, "k"));
    Map<String, String> selector = parseSelector(Args.require(args, "params"));
    int minFundingEvents = Args.intOpt(args, "min-funding-events", 6);
    int n = Args.intOpt(args, "n", 200);
    long seed = Args.longOpt(args, "seed", 42L);
    Path datasetsDir = Paths.get(Args.opt(args, "datasets-dir", "data"));
    CandleInterval interval = CandleInterval.valueOf(Args.opt(args, "interval", "ONE_HOUR"));
    String intervalTag = PortfolioCli.INTERVAL_TAG.get(interval);
    BigDecimal startingCash = Args.bigDecOpt(args, "starting-cash", new BigDecimal("10000"));
    BigDecimal noTradeBand = Args.bigDecOpt(args, "no-trade-band", new BigDecimal("0.05"));
    FeeModel fees = PortfolioCli.buildFees(args);
    RiskParameters riskParameters = PortfolioCli.riskParametersFromArgs(args);

    BacktestConfig cfg =
        new BacktestConfig(
            startingCash,
            fees,
            Args.intOpt(args, "warmup-bars", 200),
            BigDecimal.ONE,
            null,
            null,
            Args.intOpt(args, "bars-per-year", BacktestConfig.barsPerYearFor(interval)));

    Map<String, List<Candle>> perp = new LinkedHashMap<>();
    Map<String, List<Candle>> spot = new LinkedHashMap<>();
    Map<String, List<FundingEvent>> funding = new LinkedHashMap<>();
    List<String> included = new ArrayList<>();
    for (String pair : pairs) {
      Path klinePath = datasetsDir.resolve(pair + "_" + intervalTag + "_" + label + ".csv");
      Path fundingPath = datasetsDir.resolve(pair + "_funding_" + label + ".csv");
      Path spotPath = datasetsDir.resolve(pair + "_spot_" + intervalTag + "_" + label + ".csv");
      if (!Files.exists(klinePath) || !Files.exists(fundingPath) || !Files.exists(spotPath)) {
        System.out.println("[SKIP] missing dataset(s) for " + pair + " — excluding");
        continue;
      }
      try (var s = CsvKlineReader.stream(klinePath)) {
        perp.put(pair, s.toList());
      }
      try (var s = CsvKlineReader.stream(spotPath)) {
        spot.put(pair, s.toList());
      }
      try (var s = CsvFundingReader.stream(fundingPath)) {
        funding.put(pair, s.toList());
      }
      included.add(pair);
    }
    if (included.isEmpty()) {
      System.err.println(
          "REFUSED: no pair had kline+funding+spot datasets for label='" + label + "'");
      return 1;
    }

    // The real book, run once over exactly the same data the permutations will see.
    var provider =
        PortfolioParameterSpaceRegistry.load()
            .find(strategyName)
            .orElseThrow(
                () -> new IllegalArgumentException("unknown --strategy='" + strategyName + "'"));
    List<PortfolioCandidate> matches =
        provider.grid(fees).filter(c -> matchesAll(c, selector)).toList();
    if (matches.size() != 1) {
      throw new IllegalArgumentException(
          "--params must select exactly one candidate from "
              + strategyName
              + "'s grid; "
              + selector
              + " matched "
              + matches.size()
              + ". Name every searched dimension -- an under-specified selector would silently pick"
              + " an arbitrary member of the matching set as 'the' result the null model is"
              + " compared against.");
    }
    PortfolioCandidate actual = matches.get(0);
    MutableClock clock = new MutableClock(Instant.EPOCH, ZoneOffset.UTC);
    RiskManagerPort rm = new BacktestClockSync(new RiskManager(riskParameters, clock), clock);
    BacktestResult actualResult =
        PortfolioBacktestHarness.runTargets(
            perp,
            spot,
            funding,
            Map.of(),
            actual.strategy(),
            interval,
            cfg,
            rm,
            startingCash,
            actual.noTradeBand() != null ? actual.noTradeBand() : noTradeBand);
    double actualReturn = actualResult.totalReturn().doubleValue();

    Path outDir = Paths.get(Args.opt(args, "out", defaultOutDir()));
    Files.createDirectories(outDir);
    try (var writer =
        Files.newBufferedWriter(
            outDir.resolve("progress.log"), StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
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

      CrossSectionalNullModel.NullModelOutcome outcome =
          CrossSectionalNullModel.run(
              perp,
              spot,
              funding,
              interval,
              cfg,
              riskParameters,
              k,
              minFundingEvents,
              n,
              seed,
              actualReturn,
              progress);

      Map<String, Object> m = new LinkedHashMap<>();
      m.put("generatedAt", Instant.now().toString());
      m.put("strategy", strategyName);
      m.put("pairs", included);
      m.put("label", label);
      m.put("k", k);
      m.put("actualParams", actual.paramsSnapshot());
      m.put("minFundingEvents", minFundingEvents);
      m.put("n", n);
      m.put("seed", seed);
      m.put("actualReturn", actualReturn);
      m.put("nullMedian", outcome.median());
      m.put("nullP95", outcome.p95());
      m.put("percentileOfActual", outcome.percentileOfActual());
      // ADR-0016 condition 6, stated as the boolean the gate actually reads -- so a verdict note
      // never has to re-derive it from the distribution and risk getting the direction wrong.
      m.put("beatsP95", outcome.beatsP95());
      m.put("permutationReturns", outcome.permutationReturns());
      Files.writeString(outDir.resolve("null-model.json"), JsonWriter.pretty(m));

      System.out.printf(
          "null model: actual=%.6f median=%.6f p95=%.6f percentile=%.3f beatsP95=%s → %s%n",
          actualReturn,
          outcome.median(),
          outcome.p95(),
          outcome.percentileOfActual(),
          outcome.beatsP95(),
          outDir);
    }
    return 0;
  }

  /** {@code k=2,windowSize=6,minFundingEvents=10} — every key compared as a string. */
  private static Map<String, String> parseSelector(String spec) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String pair : spec.split(",")) {
      int eq = pair.indexOf('=');
      if (eq < 1) {
        throw new IllegalArgumentException("--params entry must be key=value, got: " + pair);
      }
      out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
    }
    return out;
  }

  private static boolean matchesAll(PortfolioCandidate c, Map<String, String> selector) {
    for (var e : selector.entrySet()) {
      Object v = c.paramsSnapshot().get(e.getKey());
      if (v == null || !String.valueOf(v).equals(e.getValue())) {
        return false;
      }
    }
    return true;
  }

  private static String defaultOutDir() {
    return "build/backtests/nullmodel_"
        + LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
  }
}
