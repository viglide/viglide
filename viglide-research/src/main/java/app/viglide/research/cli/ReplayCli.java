package app.viglide.research.cli;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.ExchangeFilters;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.params.JsonReader;
import app.viglide.core.params.ParamsHash;
import app.viglide.core.params.WinnersParamsLoader;
import app.viglide.core.risk.ExecutionDecision;
import app.viglide.core.risk.MutableClock;
import app.viglide.core.risk.PortfolioState;
import app.viglide.core.risk.RiskManager;
import app.viglide.core.risk.RiskParameters;
import app.viglide.core.spi.StrategyRegistry;
import app.viglide.core.spi.TradingStrategy;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PLAN-012 Task H: the replay half of the K2 instrument (review finding F16 — <em>"if live paper
 * trading diverges materially from backtest predictions over 90 days, halt and diagnose before any
 * real capital"</em> was a pre-committed kill criterion with no measuring device before this class
 * existed).
 *
 * <p>Reads a paper/testnet run's replay JSONL file ({@code ReplayRecorder}, {@code
 * viglide-runtime}) and its live ledger JSONL file ({@code JsonlLedger}), re-evaluates each
 * recorded decision-input through the deterministic {@code viglide-core} decision path — {@code
 * strategy.evaluate} then {@code riskManager.gate}, the same two calls {@code
 * LiveDecisionLoop.processCandle} makes, not routed through any multi-bar harness (those simulate
 * fills over a sequence; replay only needs one recorded snapshot re-evaluated) — and diffs the
 * replayed decision against what was actually recorded live for the same {@code (symbol, asOf)}.
 *
 * <p><strong>No {@code viglide-runtime} dependency</strong> (CLAUDE.md §3: {@code viglide-research}
 * depends on {@code viglide-core} and {@code viglide-examples} only — confirmed structurally
 * impossible to violate, not just convention-guarded, since this module's compile classpath has no
 * {@code viglide-runtime} entry at all). Both JSONL files are read with {@link JsonReader}, this
 * module's own hand-rolled parser (no Jackson on this module's classpath either) — the same one
 * {@link PromoteCli} already uses for {@code winners.json}.
 *
 * <p>Invoke as: {@code ./gradlew :viglide-research:replay --args='--replay-file=<path>
 * --live-ledger-file=<path> --strategy=<name> --symbol=<symbol> --params=textbook|winners:<path>'}
 * — see the runbook's "Running a parity check" section for the full flag list.
 */
public final class ReplayCli {

  private static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("0.00000001");

  private ReplayCli() {}

  public static void main(String[] args) throws IOException {
    int exitCode = run(args, System.out, System.err);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  /**
   * The full flow, minus the process-exiting side effect — public and returning an exit code
   * instead of calling {@link System#exit} directly, so tests (including the fundingarb-specific
   * ones in {@code viglide-strategies}, private repo, PLAN-018 R-5) can drive every path (success,
   * a real FAIL verdict, an aborted mismatched-config run) without killing the test JVM. {@code
   * main} above is the only caller that turns a non-zero result into a real exit.
   *
   * @return 0 (pass), 1 (ran to completion, parity FAIL), or 2 (aborted — recorded parameter hashes
   *     didn't match the resolved {@code --params}/{@code --rm-*} configuration)
   */
  public static int run(String[] args, java.io.PrintStream out, java.io.PrintStream err)
      throws IOException {
    Map<String, String> parsed = Args.parse(args);
    Path replayFile = Path.of(Args.require(parsed, "replay-file"));
    Path liveLedgerFile = Path.of(Args.require(parsed, "live-ledger-file"));
    String strategyName = Args.require(parsed, "strategy");
    String symbol = Args.require(parsed, "symbol");
    String paramsFlag = Args.require(parsed, "params");
    BigDecimal tolerance = Args.bigDecOpt(parsed, "tolerance", DEFAULT_TOLERANCE);

    Resolved resolved = resolve(strategyName, symbol, paramsFlag, parsed);
    List<ReplayRecord> replayRecords = readReplayRecords(replayFile);
    Map<String, LiveDecision> liveByKey = readLiveDecisions(liveLedgerFile);

    List<String> hashMismatches = findHashMismatches(replayRecords, resolved);
    if (!hashMismatches.isEmpty()) {
      hashMismatches.forEach(err::println);
      return 2;
    }

    List<DiffResult> diffs = new ArrayList<>();
    MutableClock clock = new MutableClock(Instant.EPOCH, ZoneOffset.UTC);
    RiskManager riskManager = new RiskManager(resolved.riskParameters(), clock);
    for (ReplayRecord record : replayRecords) {
      clock.set(record.at());
      diffs.add(replayOne(record, resolved.strategy(), riskManager, liveByKey, tolerance));
    }

    ParityReport report = ParityReport.summarize(diffs);
    Path reportPath =
        writeReport(
            report,
            diffs,
            replayFile,
            liveLedgerFile,
            strategyName,
            symbol,
            resolved,
            Args.opt(parsed, "out", null));

    out.println(report.summaryLine());
    out.println("Parity report written to " + reportPath);
    return report.pass() ? 0 : 1;
  }

  // ── strategy/risk-parameter resolution ──────────────────────────────────────────────────────

  private record Resolved(
      TradingStrategy strategy,
      RiskParameters riskParameters,
      String strategyParamHash,
      String riskParamHash) {}

  private static Resolved resolve(
      String strategyName, String symbol, String paramsFlag, Map<String, String> args) {
    StrategyRegistry registry = StrategyRegistry.load();
    TradingStrategy strategy;
    String strategyParamHash;
    if ("textbook".equals(paramsFlag)) {
      strategy = registry.create(strategyName, Map.of());
      strategyParamHash = "textbook";
    } else if (paramsFlag.startsWith("winners:")) {
      Path winnersPath = Path.of(paramsFlag.substring("winners:".length()));
      WinnersParamsLoader.Resolved r =
          WinnersParamsLoader.resolve(winnersPath, strategyName, symbol);
      strategy = registry.create(strategyName, r.args());
      strategyParamHash = r.paramsHash();
    } else {
      throw new IllegalArgumentException(
          "--params must be 'textbook' or 'winners:<path>', got: '" + paramsFlag + "'");
    }

    RiskParameters riskParameters = buildRiskParameters(args);
    String riskParamHash = ParamsHash.of(riskParametersAsMap(riskParameters));
    return new Resolved(strategy, riskParameters, strategyParamHash, riskParamHash);
  }

  /**
   * Mirrors {@code BacktestCli}'s {@code --rm-*} flags exactly, plus the four PLAN-012 Task F
   * absolute-dollar flags {@code BacktestCli}/{@code PortfolioCli} deliberately don't expose
   * (research runs don't model them) but a replay of a real live/paper run must be able to
   * reconstruct, since they were part of what actually gated the decisions being replayed.
   */
  private static RiskParameters buildRiskParameters(Map<String, String> args) {
    return RiskParameters.defaultsWithAbsoluteLimits(
        Args.opt(args, "rm-max-total-deployed-abs", null) == null
            ? Optional.empty()
            : Optional.of(new BigDecimal(args.get("rm-max-total-deployed-abs"))),
        Args.opt(args, "rm-max-position-abs", null) == null
            ? Optional.empty()
            : Optional.of(new BigDecimal(args.get("rm-max-position-abs"))),
        Args.opt(args, "rm-max-daily-loss-abs", null) == null
            ? Optional.empty()
            : Optional.of(new BigDecimal(args.get("rm-max-daily-loss-abs"))),
        Args.opt(args, "rm-max-campaign-loss-abs", null) == null
            ? Optional.empty()
            : Optional.of(new BigDecimal(args.get("rm-max-campaign-loss-abs"))));
  }

  // Public (not private): the fundingarb-specific replay test (viglide-strategies, private repo,
  // PLAN-018 R-5) builds its own fixtures with this exact hashing input shape, so a test-only
  // re-implementation can never silently drift from what resolve() actually hashes.
  public static Map<String, String> riskParametersAsMap(RiskParameters rp) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("maxPositionPct", rp.maxPositionPct().toPlainString());
    m.put("maxPortfolioDrawdownPct", rp.maxPortfolioDrawdownPct().toPlainString());
    m.put("maxLeverage", rp.maxLeverage().toPlainString());
    m.put("maxDailyVolumePct", rp.maxDailyVolumePct().toPlainString());
    m.put("maxPortfolioRiskPct", rp.maxPortfolioRiskPct().toPlainString());
    m.put("stopLossAtrMult", String.valueOf(rp.stopLossAtrMult()));
    m.put("confidenceFloor", String.valueOf(rp.confidenceFloor()));
    m.put("maxStaleInputAge", rp.maxStaleInputAge().toString());
    m.put(
        "maxTotalDeployedAbs",
        rp.maxTotalDeployedAbs().map(BigDecimal::toPlainString).orElse("absent"));
    m.put("maxPositionAbs", rp.maxPositionAbs().map(BigDecimal::toPlainString).orElse("absent"));
    m.put("maxDailyLossAbs", rp.maxDailyLossAbs().map(BigDecimal::toPlainString).orElse("absent"));
    m.put(
        "maxCampaignLossAbs",
        rp.maxCampaignLossAbs().map(BigDecimal::toPlainString).orElse("absent"));
    return m;
  }

  // ── replay-file parsing ─────────────────────────────────────────────────────────────────────

  record ReplayRecord(
      Instant at,
      String symbol,
      Instant asOf,
      CandleInterval interval,
      List<Candle> candles,
      List<FundingEvent> fundingHistory,
      Optional<BigDecimal> lastAtr,
      Optional<ExchangeFilters> exchangeFilters,
      PortfolioState portfolioState,
      String strategyParamHash,
      String riskParamHash) {}

  @SuppressWarnings("unchecked")
  static List<ReplayRecord> readReplayRecords(Path file) throws IOException {
    List<ReplayRecord> out = new ArrayList<>();
    for (String line : Files.readAllLines(file)) {
      if (line.isBlank()) {
        continue;
      }
      Map<String, Object> envelope = (Map<String, Object>) JsonReader.parse(line);
      if (!"decision-input".equals(envelope.get("kind"))) {
        continue; // run-header / heartbeat
      }
      Instant at = Instant.parse((String) envelope.get("at"));
      Map<String, Object> p = (Map<String, Object>) envelope.get("payload");
      out.add(
          new ReplayRecord(
              at,
              (String) p.get("symbol"),
              Instant.parse((String) p.get("asOf")),
              CandleInterval.valueOf((String) p.get("interval")),
              parseCandles((List<Object>) p.get("candles")),
              parseFundingEvents((List<Object>) p.get("fundingHistory")),
              optionalBigDecimal(p.get("lastAtr")),
              parseExchangeFilters((Map<String, Object>) p.get("exchangeFilters")),
              parsePortfolioState((Map<String, Object>) p.get("portfolioState")),
              (String) p.get("strategyParamHash"),
              (String) p.get("riskParamHash")));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static List<Candle> parseCandles(List<Object> raw) {
    List<Candle> out = new ArrayList<>(raw.size());
    for (Object o : raw) {
      Map<String, Object> c = (Map<String, Object>) o;
      out.add(
          new Candle(
              Instant.parse((String) c.get("openTime")),
              new BigDecimal((String) c.get("open")),
              new BigDecimal((String) c.get("high")),
              new BigDecimal((String) c.get("low")),
              new BigDecimal((String) c.get("close")),
              new BigDecimal((String) c.get("volume"))));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static List<FundingEvent> parseFundingEvents(List<Object> raw) {
    List<FundingEvent> out = new ArrayList<>(raw.size());
    for (Object o : raw) {
      Map<String, Object> f = (Map<String, Object>) o;
      out.add(
          new FundingEvent(
              Instant.parse((String) f.get("time")), new BigDecimal((String) f.get("rate"))));
    }
    return out;
  }

  private static Optional<ExchangeFilters> parseExchangeFilters(Map<String, Object> m) {
    if (m == null) {
      return Optional.empty();
    }
    return Optional.of(
        new ExchangeFilters(
            new BigDecimal((String) m.get("minNotional")),
            new BigDecimal((String) m.get("qtyStepSize")),
            new BigDecimal((String) m.get("priceTickSize"))));
  }

  @SuppressWarnings("unchecked")
  private static PortfolioState parsePortfolioState(Map<String, Object> m) {
    Map<String, Object> rawPositions = (Map<String, Object>) m.get("openPositions");
    Map<String, BigDecimal> positions = new LinkedHashMap<>();
    rawPositions.forEach((sym, v) -> positions.put(sym, new BigDecimal((String) v)));
    return new PortfolioState(
        new BigDecimal((String) m.get("cash")),
        new BigDecimal((String) m.get("equity")),
        new BigDecimal((String) m.get("peakEquity")),
        positions,
        (Boolean) m.get("circuitBreakerTripped"),
        optionalBigDecimal(m.get("dayStartEquity")));
  }

  private static Optional<BigDecimal> optionalBigDecimal(Object v) {
    return v == null ? Optional.empty() : Optional.of(new BigDecimal((String) v));
  }

  // ── live-ledger-file parsing ────────────────────────────────────────────────────────────────

  record LiveDecision(String decision, String side, BigDecimal size, String reason) {}

  @SuppressWarnings("unchecked")
  static Map<String, LiveDecision> readLiveDecisions(Path file) throws IOException {
    Map<String, LiveDecision> out = new LinkedHashMap<>();
    for (String line : Files.readAllLines(file)) {
      if (line.isBlank()) {
        continue;
      }
      Map<String, Object> envelope = (Map<String, Object>) JsonReader.parse(line);
      String kind = (String) envelope.get("kind");
      if (!"trade".equals(kind) && !"refusal".equals(kind)) {
        continue; // watchdog rows never come from riskManager.gate() -- nothing to replay-compare
      }
      Map<String, Object> p = (Map<String, Object>) envelope.get("payload");
      String key = joinKey((String) p.get("symbol"), (String) p.get("asOf"));
      out.put(
          key,
          new LiveDecision(
              (String) p.get("decision"),
              (String) p.get("side"),
              p.get("size") == null ? null : new BigDecimal((String) p.get("size")),
              (String) p.get("reason")));
    }
    return out;
  }

  private static String joinKey(String symbol, String asOf) {
    return symbol + "|" + asOf;
  }

  // ── validation ───────────────────────────────────────────────────────────────────────────────

  /**
   * Checked before replaying a single record: do the resolved strategy/risk parameters match what
   * the replay file itself recorded? A mismatch means "you replayed against a different
   * configuration" — a false positive against K2, not a nondeterminism finding — so {@link #run}
   * aborts (exit code 2) on any non-empty result rather than proceeding. Checked against every
   * record (not just the first) in case a replay file somehow spans more than one parameter regime.
   * Returns messages rather than printing/exiting directly so {@link #run} stays testable.
   */
  private static List<String> findHashMismatches(List<ReplayRecord> records, Resolved resolved) {
    List<String> mismatches = new ArrayList<>();
    for (ReplayRecord r : records) {
      if (!r.strategyParamHash().equals(resolved.strategyParamHash())) {
        mismatches.add(
            "ABORT: recorded strategyParamHash '"
                + r.strategyParamHash()
                + "' does not match the resolved --params configuration's hash '"
                + resolved.strategyParamHash()
                + "'. This replay run was invoked with different strategy parameters than the"
                + " live run used -- fix --params, don't proceed (a mismatch here is not evidence"
                + " of nondeterminism).");
      }
      if (!r.riskParamHash().equals(resolved.riskParamHash())) {
        mismatches.add(
            "ABORT: recorded riskParamHash '"
                + r.riskParamHash()
                + "' does not match the resolved --rm-* configuration's hash '"
                + resolved.riskParamHash()
                + "'. This replay run was invoked with different Risk Manager parameters than the"
                + " live run used -- fix the --rm-* flags, don't proceed.");
      }
    }
    return mismatches;
  }

  // ── replay + diff ────────────────────────────────────────────────────────────────────────────

  enum MatchCategory {
    EXACT_MATCH,
    WITHIN_TOLERANCE,
    DIRECTION_MISMATCH,
    SIZE_MISMATCH,
    REFUSAL_REASON_MISMATCH,
    UNMATCHED
  }

  record DiffResult(
      String symbol,
      Instant asOf,
      MatchCategory category,
      String liveSummary,
      String replaySummary) {}

  private static DiffResult replayOne(
      ReplayRecord record,
      TradingStrategy strategy,
      RiskManager riskManager,
      Map<String, LiveDecision> liveByKey,
      BigDecimal tolerance) {
    LiveDecision live = liveByKey.get(joinKey(record.symbol(), record.asOf().toString()));

    MarketContext ctx =
        new MarketContext(
            record.symbol(),
            record.interval(),
            record.candles(),
            record.fundingHistory(),
            record.lastAtr(),
            record.exchangeFilters());
    Optional<TechnicalSignal> maybeSignal = strategy.evaluate(ctx);
    if (maybeSignal.isEmpty() || maybeSignal.get().direction() == Direction.HOLD) {
      return new DiffResult(
          record.symbol(),
          record.asOf(),
          MatchCategory.DIRECTION_MISMATCH,
          describeLive(live),
          "no signal (strategy returned empty or HOLD on replay)");
    }
    TechnicalSignal signal = maybeSignal.get();
    ExecutionDecision replayed = riskManager.gate(signal, record.portfolioState(), ctx);

    if (live == null) {
      return new DiffResult(
          record.symbol(),
          record.asOf(),
          MatchCategory.UNMATCHED,
          "none",
          describeReplayed(replayed));
    }

    boolean liveIsExecute = "EXECUTE".equals(live.decision());
    boolean replayedIsExecute = replayed instanceof ExecutionDecision.Execute;
    if (liveIsExecute != replayedIsExecute) {
      return new DiffResult(
          record.symbol(),
          record.asOf(),
          MatchCategory.DIRECTION_MISMATCH,
          describeLive(live),
          describeReplayed(replayed));
    }

    if (replayedIsExecute) {
      ExecutionDecision.Execute rExec = (ExecutionDecision.Execute) replayed;
      if (!rExec.side().name().equals(live.side())) {
        return new DiffResult(
            record.symbol(),
            record.asOf(),
            MatchCategory.DIRECTION_MISMATCH,
            describeLive(live),
            describeReplayed(replayed));
      }
      BigDecimal sizeDiff = rExec.size().subtract(live.size()).abs();
      MatchCategory category =
          sizeDiff.signum() == 0
              ? MatchCategory.EXACT_MATCH
              : sizeDiff.compareTo(tolerance) <= 0
                  ? MatchCategory.WITHIN_TOLERANCE
                  : MatchCategory.SIZE_MISMATCH;
      return new DiffResult(
          record.symbol(), record.asOf(), category, describeLive(live), describeReplayed(replayed));
    } else {
      ExecutionDecision.Refuse rRefuse = (ExecutionDecision.Refuse) replayed;
      MatchCategory category =
          rRefuse.reason().name().equals(live.reason())
              ? MatchCategory.EXACT_MATCH
              : MatchCategory.REFUSAL_REASON_MISMATCH;
      return new DiffResult(
          record.symbol(), record.asOf(), category, describeLive(live), describeReplayed(replayed));
    }
  }

  private static String describeLive(LiveDecision live) {
    if (live == null) {
      return "none";
    }
    return live.decision().equals("EXECUTE")
        ? "EXECUTE " + live.side() + " size=" + live.size()
        : "REFUSE " + live.reason();
  }

  private static String describeReplayed(ExecutionDecision decision) {
    if (decision instanceof ExecutionDecision.Execute e) {
      return "EXECUTE " + e.side() + " size=" + e.size().toPlainString();
    }
    ExecutionDecision.Refuse r = (ExecutionDecision.Refuse) decision;
    return "REFUSE " + r.reason();
  }

  // ── parity report ────────────────────────────────────────────────────────────────────────────

  record ParityReport(Map<MatchCategory, Long> counts, int total) {
    static ParityReport summarize(List<DiffResult> diffs) {
      Map<MatchCategory, Long> counts = new LinkedHashMap<>();
      for (MatchCategory c : MatchCategory.values()) {
        counts.put(c, 0L);
      }
      for (DiffResult d : diffs) {
        counts.merge(d.category(), 1L, Long::sum);
      }
      return new ParityReport(counts, diffs.size());
    }

    /**
     * Per Task H: any direction/refusal-reason mismatch, or any unmatched record, is a hard fail.
     */
    boolean pass() {
      return counts.get(MatchCategory.DIRECTION_MISMATCH) == 0
          && counts.get(MatchCategory.REFUSAL_REASON_MISMATCH) == 0
          && counts.get(MatchCategory.UNMATCHED) == 0;
    }

    String summaryLine() {
      return (pass() ? "PASS" : "FAIL")
          + ": "
          + total
          + " replayed, "
          + counts.get(MatchCategory.EXACT_MATCH)
          + " exact, "
          + counts.get(MatchCategory.WITHIN_TOLERANCE)
          + " within-tolerance, "
          + counts.get(MatchCategory.DIRECTION_MISMATCH)
          + " direction-mismatch, "
          + counts.get(MatchCategory.SIZE_MISMATCH)
          + " size-mismatch, "
          + counts.get(MatchCategory.REFUSAL_REASON_MISMATCH)
          + " refusal-reason-mismatch, "
          + counts.get(MatchCategory.UNMATCHED)
          + " unmatched";
    }
  }

  private static Path writeReport(
      ParityReport report,
      List<DiffResult> diffs,
      Path replayFile,
      Path liveLedgerFile,
      String strategyName,
      String symbol,
      Resolved resolved,
      String outOverride)
      throws IOException {
    String runId =
        replayFile.getParent() == null ? "run" : replayFile.getParent().getFileName().toString();
    Path out =
        outOverride != null
            ? Path.of(outOverride)
            : Path.of(
                "docs",
                "notes",
                LocalDate.now(ZoneOffset.UTC) + "-plan012-taskh-parity-report-" + runId + ".md");

    StringBuilder md = new StringBuilder();
    md.append("# PLAN-012 Task H — Replay Parity Report (").append(runId).append(")\n\n");
    md.append("> Source: replay file `")
        .append(replayFile)
        .append("`, live ledger `")
        .append(liveLedgerFile)
        .append("`.\n");
    md.append("> Strategy `").append(strategyName).append("` on `").append(symbol).append("`. ");
    md.append("strategyParamHash=`").append(resolved.strategyParamHash()).append("` ");
    md.append("riskParamHash=`").append(resolved.riskParamHash()).append("` ");
    md.append(
        "(both verified against the replay file's own recorded hashes before this report was produced).\n\n");
    md.append("## Summary\n\n");
    md.append("| Category | Count |\n|---|---|\n");
    for (MatchCategory c : MatchCategory.values()) {
      md.append("| ").append(c.name()).append(" | ").append(report.counts().get(c)).append(" |\n");
    }
    md.append("| **Total replayed** | ").append(report.total()).append(" |\n\n");
    md.append("**Verdict: ").append(report.pass() ? "PASS" : "FAIL").append("**\n\n");
    md.append("## Detail (non-exact-match records only)\n\n");
    md.append("| Symbol | asOf | Live | Replayed | Category |\n|---|---|---|---|---|\n");
    for (DiffResult d : diffs) {
      if (d.category() == MatchCategory.EXACT_MATCH) {
        continue;
      }
      md.append("| ")
          .append(d.symbol())
          .append(" | ")
          .append(d.asOf())
          .append(" | ")
          .append(d.liveSummary())
          .append(" | ")
          .append(d.replaySummary())
          .append(" | ")
          .append(d.category())
          .append(" |\n");
    }
    md.append("\n## Reproduce this report\n\n```bash\n./gradlew :viglide-research:replay --args=\"")
        .append("--replay-file=")
        .append(replayFile)
        .append(" --live-ledger-file=")
        .append(liveLedgerFile)
        .append(" --strategy=")
        .append(strategyName)
        .append(" --symbol=")
        .append(symbol)
        .append("\"\n```\n");

    Files.createDirectories(out.getParent());
    Files.writeString(out, md.toString());
    return out;
  }
}
