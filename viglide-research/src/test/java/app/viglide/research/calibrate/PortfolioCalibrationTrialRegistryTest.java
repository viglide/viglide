package app.viglide.research.calibrate;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.FeeModel;
import app.viglide.core.calibrate.PortfolioCandidate;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.domain.PortfolioContext;
import app.viglide.core.domain.PositionShape;
import app.viglide.core.domain.TargetPosition;
import app.viglide.core.spi.PortfolioStrategy;
import app.viglide.core.spi.StrategyMetadata;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves {@link TrialRegistry} integration for a {@link PortfolioCalibrationHarness} run (PLAN-019
 * Task D acceptance criterion) — the harness itself does not call {@link TrialRegistry} (same
 * layering {@link PanelCalibrationHarness} already uses: the registry call belongs to whichever
 * caller knows the dataset fingerprint, not the harness), so this test drives it exactly as a real
 * caller would: run the harness, then record the candidate count against a combined multi-symbol
 * fingerprint, then verify the cumulative count accumulates across repeated runs against the same
 * data.
 */
class PortfolioCalibrationTrialRegistryTest {

  private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");

  private record FixedWeightStrategy(String symbol) implements PortfolioStrategy {
    @Override
    public List<TargetPosition> evaluate(PortfolioContext context) {
      if (!context.bySymbol().containsKey(symbol)) {
        return List.of();
      }
      return List.of(
          new TargetPosition(
              symbol, BigDecimal.ONE, PositionShape.DELTA_NEUTRAL_CARRY, List.of(), "fixed"));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("trial-registry-test", "1.0", "test-only");
    }
  }

  @Test
  void run_thenAppendToTrialRegistry_accumulatesAcrossRuns(@TempDir Path tmp) throws IOException {
    String symbol = "AAA";
    List<Candle> perp = candles(80);
    List<Candle> spot = candles(80);
    Map<String, List<FundingEvent>> funding =
        Map.of(symbol, List.of(new FundingEvent(T0.plusSeconds(3600), new BigDecimal("0.001"))));
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 15, new BigDecimal("1.0"), null, null, 8760);

    Path perpFile = tmp.resolve("AAA_1h.csv");
    Files.writeString(perpFile, "synthetic perp dataset v1");
    List<PortfolioCandidate> candidates =
        List.of(new PortfolioCandidate(new FixedWeightStrategy(symbol), Map.of("variant", "only")));

    // A combined fingerprint for the whole panel -- one TrialRegistry.datasetFingerprint call per
    // symbol, joined and re-hashed, since the registry's own fingerprint function is single-symbol
    // by signature. The panel's trial cost is spent as a unit, not per symbol independently.
    String panelFingerprint =
        panelFingerprint(List.of(symbol), "ONE_HOUR", Map.of(symbol, perpFile));

    Path registryPath = tmp.resolve("research-trials.jsonl");
    assertThat(TrialRegistry.cumulativeTrialsFor(registryPath, panelFingerprint)).isEqualTo(0);

    List<PortfolioCalibrationResult> r1 =
        PortfolioCalibrationHarness.run(
            Map.of(symbol, perp),
            funding,
            Map.of(symbol, spot),
            4,
            0,
            0,
            CandleInterval.ONE_HOUR,
            cfg,
            new BigDecimal("10000"),
            new BigDecimal("0.05"),
            candidates,
            PortfolioScoringFunction.CARRY_YIELD);
    TrialRegistry.append(
        registryPath,
        new TrialRegistry.TrialRecord(
            Instant.now(), "CarryRanking", panelFingerprint, candidates.size(), 1L, "carry-yield"));

    assertThat(TrialRegistry.cumulativeTrialsFor(registryPath, panelFingerprint))
        .isEqualTo(candidates.size());
    assertThat(r1).isNotNull(); // the harness result itself is not the thing under test here

    // A second, independent run against the SAME dataset (e.g. a later development session)
    // accumulates on top of the first, exactly like the single-symbol registry's own contract.
    List<PortfolioCandidate> secondBatch =
        List.of(
            new PortfolioCandidate(new FixedWeightStrategy(symbol), Map.of("variant", "a")),
            new PortfolioCandidate(new FixedWeightStrategy(symbol), Map.of("variant", "b")));
    PortfolioCalibrationHarness.run(
        Map.of(symbol, perp),
        funding,
        Map.of(symbol, spot),
        4,
        0,
        0,
        CandleInterval.ONE_HOUR,
        cfg,
        new BigDecimal("10000"),
        new BigDecimal("0.05"),
        secondBatch,
        PortfolioScoringFunction.CARRY_YIELD);
    TrialRegistry.append(
        registryPath,
        new TrialRegistry.TrialRecord(
            Instant.now(),
            "CarryRanking",
            panelFingerprint,
            secondBatch.size(),
            2L,
            "carry-yield"));

    assertThat(TrialRegistry.cumulativeTrialsFor(registryPath, panelFingerprint))
        .isEqualTo(candidates.size() + secondBatch.size());

    // A run against a DIFFERENT dataset fingerprint must not pollute this one's count.
    Path otherFile = tmp.resolve("BBB_1h.csv");
    Files.writeString(otherFile, "a different dataset entirely");
    String otherFingerprint =
        panelFingerprint(List.of("BBB"), "ONE_HOUR", Map.of("BBB", otherFile));
    TrialRegistry.append(
        registryPath,
        new TrialRegistry.TrialRecord(
            Instant.now(), "CarryRanking", otherFingerprint, 5, 3L, "carry-yield"));
    assertThat(TrialRegistry.cumulativeTrialsFor(registryPath, panelFingerprint))
        .isEqualTo(candidates.size() + secondBatch.size());
    assertThat(TrialRegistry.cumulativeTrialsFor(registryPath, otherFingerprint)).isEqualTo(5);
  }

  /**
   * Combines each symbol's own {@link TrialRegistry#datasetFingerprint} into one panel-level
   * fingerprint (sorted symbol order, so panel identity does not depend on map iteration order,
   * NFR-7) — the exact pattern a real {@code PortfolioCalibrateCli} would use.
   */
  private static String panelFingerprint(
      List<String> symbols, String interval, Map<String, Path> datasetBySymbol) {
    List<String> orderedSymbols = new ArrayList<>(symbols);
    java.util.Collections.sort(orderedSymbols);
    StringBuilder combined = new StringBuilder();
    for (String s : orderedSymbols) {
      combined
          .append(TrialRegistry.datasetFingerprint(s, interval, datasetBySymbol.get(s)))
          .append('|');
    }
    return sha256Hex(combined.toString());
  }

  private static String sha256Hex(String s) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) hex.append(String.format("%02x", b));
      return hex.substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<Candle> candles(int bars) {
    List<Candle> out = new ArrayList<>(bars);
    for (int i = 0; i < bars; i++) {
      BigDecimal price = new BigDecimal(i % 2 == 0 ? "100" : "101");
      Instant t = T0.plusSeconds(3600L * i);
      out.add(new Candle(t, price, price, price, price, new BigDecimal("1000000")));
    }
    return out;
  }
}
