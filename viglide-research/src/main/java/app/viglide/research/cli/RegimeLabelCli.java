package app.viglide.research.cli;

import app.viglide.core.data.CsvFundingReader;
import app.viglide.core.data.CsvKlineReader;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.params.JsonWriter;
import app.viglide.core.regime.MonthlyRegime;
import app.viglide.core.regime.RegimeLabeler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Labels every calendar month of a pair's hourly history with a funding/volatility regime (PLAN-011
 * Task I, finding F9) — {@link RegimeLabeler} existed and {@code LiveDecisionLoop} logs labels
 * live, but there was no offline entry point to turn a pair's full history into the per-regime
 * aggregation tables (return/PSR/trades by regime × strategy) that would make "2025-26 idle because
 * funding compressed" a quantified finding instead of a qualitative footnote. Thin wrapper — all
 * the labeling logic is {@link RegimeLabeler#labelMonthly}; this CLI only handles CSV I/O and JSON
 * serialisation so a Python report generator can join the output against existing backtest results.
 *
 * <p>Required args:
 *
 * <ul>
 *   <li>{@code --symbol=BTCUSDT}
 *   <li>{@code --dataset=<path>} — hourly kline CSV (ascending {@code openTime}); ideally the
 *       pair's full multi-year history, since the trailing-30d window needs history before the
 *       first labeled month to be accurate there
 *   <li>{@code --funding-dataset=<path>} — funding-rate CSV, same span; an empty file is fine
 *       (every month's funding regime is then {@code UNKNOWN})
 *   <li>{@code --out=<path>} — output JSON path (one object per labeled month)
 * </ul>
 */
public final class RegimeLabelCli {

  private RegimeLabelCli() {}

  public static void main(String[] argv) throws IOException {
    System.exit(run(argv));
  }

  static int run(String[] argv) throws IOException {
    Map<String, String> args = Args.parse(argv);
    String symbol = Args.require(args, "symbol");
    Path datasetPath = Paths.get(Args.require(args, "dataset"));
    Path fundingPath = Paths.get(Args.require(args, "funding-dataset"));
    Path outPath = Paths.get(Args.require(args, "out"));

    List<Candle> candles;
    try (Stream<Candle> s = CsvKlineReader.stream(datasetPath)) {
      candles = s.toList();
    }
    List<FundingEvent> funding;
    try (Stream<FundingEvent> s = CsvFundingReader.stream(fundingPath)) {
      funding = s.toList();
    }

    List<MonthlyRegime> regimes = RegimeLabeler.labelMonthly(candles, funding);

    List<Map<String, Object>> rows = new ArrayList<>(regimes.size());
    for (MonthlyRegime r : regimes) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("symbol", symbol);
      row.put("month", r.month().toString());
      row.put("asOf", r.asOf().toString());
      row.put("fundingRegime", r.funding().name());
      row.put("volatilityRegime", r.volatility().name());
      rows.add(row);
    }

    if (outPath.getParent() != null) {
      Files.createDirectories(outPath.getParent());
    }
    Files.writeString(outPath, JsonWriter.pretty(rows));
    System.out.println(
        "regime-label: symbol=" + symbol + " months=" + rows.size() + " -> " + outPath);
    return 0;
  }
}
