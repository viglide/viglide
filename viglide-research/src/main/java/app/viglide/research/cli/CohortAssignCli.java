package app.viglide.research.cli;

import app.viglide.core.data.CsvKlineReader;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.params.JsonWriter;
import app.viglide.research.cohort.Cohort;
import app.viglide.research.cohort.CohortAssigner;
import app.viglide.research.cohort.CohortAssignment;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs {@link CohortAssigner} against the real spot corpus and reports each pair's cohort
 * membership at the start of every OOS year (PLAN-024 Task B). Reads spot klines only — PLAN-024
 * §2.2 is spot-only — one year-labelled CSV file per pair per {@code --years} entry, concatenated
 * in the order given (the corpus's own per-year files are already chronologically sorted).
 *
 * <p>Invoke as: {@code ./gradlew :viglide-strategies:cohortAssign --args='--pairs=BTCUSDT,ETHUSDT
 * --years=2021,2022,2023,2024,2025,2026H1 --datasets-dir=data --interval=ONE_HOUR
 * --lookback-days=30 --top-k-majors=2 --out=docs/notes'}
 */
public final class CohortAssignCli {

  private CohortAssignCli() {}

  public static void main(String[] argv) throws IOException {
    run(argv, System.out);
  }

  public static void run(String[] argv, PrintStream out) throws IOException {
    Map<String, String> args = Args.parse(argv);
    List<String> pairs = List.of(Args.require(args, "pairs").split(","));
    List<String> years = List.of(Args.require(args, "years").split(","));
    Path datasetsDir = Paths.get(Args.opt(args, "datasets-dir", "data"));
    CandleInterval interval = CandleInterval.valueOf(Args.opt(args, "interval", "ONE_HOUR"));
    String intervalTag = PortfolioCli.INTERVAL_TAG.get(interval);
    int lookbackDays = Args.intOpt(args, "lookback-days", 30);
    int topKMajors = Args.intOpt(args, "top-k-majors", 2);

    Map<String, List<Candle>> spotBySymbol = new LinkedHashMap<>();
    List<String> included = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (String pair : pairs) {
      List<Candle> concatenated = new ArrayList<>();
      boolean anyFound = false;
      for (String label : years) {
        Path spotPath = datasetsDir.resolve(pair + "_spot_" + intervalTag + "_" + label + ".csv");
        if (!Files.exists(spotPath)) {
          continue;
        }
        anyFound = true;
        try (var s = CsvKlineReader.stream(spotPath)) {
          concatenated.addAll(s.toList());
        }
      }
      if (!anyFound) {
        out.println("[SKIP] no spot datasets found for " + pair);
        skipped.add(pair);
        continue;
      }
      spotBySymbol.put(pair, concatenated);
      included.add(pair);
    }
    if (included.isEmpty()) {
      throw new IllegalStateException("no spot datasets found for any of " + pairs);
    }

    List<Instant> evaluationInstants = new ArrayList<>();
    for (String label : years) {
      if (label.matches("\\d{4}")) {
        int year = Integer.parseInt(label);
        evaluationInstants.add(
            ZonedDateTime.of(year, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant());
      }
    }

    Map<Instant, List<CohortAssignment>> byWindow =
        CohortAssigner.assignPerWindow(
            spotBySymbol, evaluationInstants, Duration.ofDays(lookbackDays), topKMajors);

    Map<String, Object> json = new LinkedHashMap<>();
    json.put(
        "rule", "ADV (average daily dollar volume), spot candles, top-" + topKMajors + " = MAJOR");
    json.put("interval", interval.name());
    json.put("lookbackDays", lookbackDays);
    json.put("topKMajors", topKMajors);
    json.put("pairsIncluded", included);
    json.put("pairsSkipped", skipped);
    Map<String, Object> windowsJson = new TreeMap<>();
    for (Map.Entry<Instant, List<CohortAssignment>> e : byWindow.entrySet()) {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (CohortAssignment a : e.getValue()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pair", a.pair());
        row.put("cohort", a.cohort().name());
        row.put("advRank", a.advRank());
        row.put("adv", a.adv());
        rows.add(row);
      }
      windowsJson.put(e.getKey().toString(), rows);
    }
    json.put("assignmentsByWindow", windowsJson);

    out.println(
        "cohort-assign: pairs=" + included.size() + " windows=" + evaluationInstants.size());
    for (Map.Entry<Instant, List<CohortAssignment>> e : new TreeMap<>(byWindow).entrySet()) {
      String majors =
          e.getValue().stream()
              .filter(a -> a.cohort() == Cohort.MAJOR)
              .map(CohortAssignment::pair)
              .reduce((a, b) -> a + "," + b)
              .orElse("");
      out.println("  " + e.getKey() + " MAJOR=[" + majors + "]");
    }

    String outArg = args.get("out");
    if (outArg != null && !outArg.isBlank()) {
      Path outDir = Paths.get(outArg);
      Files.createDirectories(outDir);
      Files.writeString(outDir.resolve("cohort-assignment.json"), JsonWriter.pretty(json));
      out.println("wrote " + outDir.resolve("cohort-assignment.json"));
    }
  }
}
