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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Synthetic-fixture end-to-end tests for {@link PortfolioCalibrationHarness} (PLAN-019 Task D). */
class PortfolioCalibrationHarnessTest {

  private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");

  /**
   * Toggles a symbol's DELTA_NEUTRAL_CARRY weight on for {@code onFor} hours, then flat for the
   * same span, repeating -- unlike a constant-weight strategy, this produces real voluntary closes
   * within a fold (not just an END_OF_DATA artifact at the fold boundary, which {@link
   * PortfolioCalibrationHarness} correctly excludes from scoring, same as {@link
   * CalibrationHarness}). Derived purely from {@code context.asOf()} rather than an internal call
   * counter -- {@link PortfolioStrategy}'s own contract requires "identical context in, identical
   * output out," and a call-counter would violate it the moment the same strategy instance is
   * reused across more than one fold or run, which this harness (like {@link CalibrationHarness}
   * before it) does by design.
   */
  private record TogglingWeightStrategy(String symbol, int onFor) implements PortfolioStrategy {
    @Override
    public List<TargetPosition> evaluate(PortfolioContext context) {
      long hour = context.asOf().getEpochSecond() / 3600L;
      boolean on = (hour / onFor) % 2 == 0;
      if (!on || !context.bySymbol().containsKey(symbol)) {
        return List.of();
      }
      return List.of(
          new TargetPosition(
              symbol, BigDecimal.ONE, PositionShape.DELTA_NEUTRAL_CARRY, List.of(), "toggle"));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("toggling-weight-test", "1.0", "test-only");
    }
  }

  private static final class NeverTradesStrategy implements PortfolioStrategy {
    @Override
    public List<TargetPosition> evaluate(PortfolioContext context) {
      return List.of();
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("never-trades-test", "1.0", "test-only");
    }
  }

  private static PortfolioCandidate candidate(String symbol, int onFor, String label) {
    return new PortfolioCandidate(
        new TogglingWeightStrategy(symbol, onFor), Map.of("variant", label));
  }

  @Test
  void run_fitsPortfolioStrategyAcrossPanel_ranksDescendingByScore_stableTieBreak() {
    String symbol = "AAA";
    List<Candle> perp = alternatingCandles(80);
    List<Candle> spot = alternatingCandles(80);
    List<FundingEvent> funding = fundingEvery(80, 2);
    BacktestConfig cfg = smallCapCfg();

    // Two candidates with byte-identical strategy behaviour but different labels -- their scores
    // must tie exactly, so ranking falls back to the stable params key (ascending: "A" before "B").
    PortfolioCandidate tieA = candidate(symbol, 2, "A");
    PortfolioCandidate tieB = candidate(symbol, 2, "B");
    // A different toggle period -> genuinely different trade pattern.
    PortfolioCandidate other = candidate(symbol, 4, "other");
    // Never targets the symbol at all -- must be filtered out entirely by minTrades.
    PortfolioCandidate never =
        new PortfolioCandidate(new NeverTradesStrategy(), Map.of("variant", "never"));

    List<PortfolioCalibrationResult> results =
        PortfolioCalibrationHarness.run(
            Map.of(symbol, perp),
            Map.of(symbol, funding),
            Map.of(symbol, spot),
            4,
            0,
            1,
            CandleInterval.ONE_HOUR,
            cfg,
            new BigDecimal("10000"),
            new BigDecimal("0.05"),
            List.of(tieA, other, tieB, never),
            PortfolioScoringFunction.CARRY_YIELD);

    assertThat(results).hasSizeGreaterThanOrEqualTo(2);
    assertThat(results.stream().map(r -> r.params().get("variant"))).doesNotContain("never");
    for (PortfolioCalibrationResult r : results) {
      assertThat(r.foldsEvaluated()).isEqualTo(4);
      assertThat(r.cvTradeCountTotal()).isGreaterThan(0);
    }
    // Scores are non-increasing down the ranked list.
    for (int i = 1; i < results.size(); i++) {
      assertThat(PortfolioScoringFunction.CARRY_YIELD.score(results.get(i - 1)))
          .isGreaterThanOrEqualTo(PortfolioScoringFunction.CARRY_YIELD.score(results.get(i)));
    }
    // The two tied candidates ("A" and "B") must appear adjacent, numerically identical, and in
    // ascending label order among themselves (the stable tie-break).
    List<String> labels = results.stream().map(r -> (String) r.params().get("variant")).toList();
    int idxA = labels.indexOf("A");
    int idxB = labels.indexOf("B");
    assertThat(idxA).isGreaterThanOrEqualTo(0);
    assertThat(idxB).isEqualTo(idxA + 1);
    PortfolioCalibrationResult a = results.get(idxA);
    PortfolioCalibrationResult b = results.get(idxB);
    assertThat(a.cvSharpeMedian()).isEqualTo(b.cvSharpeMedian());
    assertThat(a.cvReturnOnDeployedCapitalPooled())
        .isEqualByComparingTo(b.cvReturnOnDeployedCapitalPooled());
    assertThat(a.cvTradeCountTotal()).isEqualTo(b.cvTradeCountTotal());
  }

  @Test
  void run_isDeterministic_sameInputsProduceIdenticalResults() {
    String symbol = "AAA";
    List<Candle> perp = alternatingCandles(80);
    List<Candle> spot = alternatingCandles(80);
    Map<String, List<FundingEvent>> funding = Map.of(symbol, fundingEvery(80, 2));
    BacktestConfig cfg = smallCapCfg();
    List<PortfolioCandidate> candidates = List.of(candidate(symbol, 3, "solo"));

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
    List<PortfolioCalibrationResult> r2 =
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

    assertThat(r1).isEqualTo(r2);
    assertThat(r1.get(0).cvTradeCountTotal()).isGreaterThan(0);
  }

  @Test
  void run_injectableScoringFunction_usesItsOwnFormula() {
    String symbol = "AAA";
    List<Candle> perp = alternatingCandles(80);
    List<Candle> spot = alternatingCandles(80);
    Map<String, List<FundingEvent>> funding = Map.of(symbol, fundingEvery(80, 2));
    BacktestConfig cfg = smallCapCfg();
    List<PortfolioCandidate> candidates = List.of(candidate(symbol, 3, "solo"));

    List<PortfolioCalibrationResult> byCarryYield =
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
    List<PortfolioCalibrationResult> byMedianSharpe =
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
            PortfolioScoringFunction.MEDIAN_CV_SHARPE);

    PortfolioCalibrationResult r = byCarryYield.get(0);
    double expectedCarryYield =
        r.cvReturnOnDeployedCapitalPooled().doubleValue() / (1.0 + r.cvUlcerIndexMedian());
    assertThat(PortfolioScoringFunction.CARRY_YIELD.score(r)).isEqualTo(expectedCarryYield);
    assertThat(PortfolioScoringFunction.MEDIAN_CV_SHARPE.score(byMedianSharpe.get(0)))
        .isEqualTo(byMedianSharpe.get(0).cvSharpeMedian());
  }

  private static BacktestConfig smallCapCfg() {
    return new BacktestConfig(
        new BigDecimal("10000"), FeeModel.zero(), 15, new BigDecimal("1.0"), null, null, 8760);
  }

  private static List<Candle> alternatingCandles(int bars) {
    List<Candle> out = new ArrayList<>(bars);
    for (int i = 0; i < bars; i++) {
      BigDecimal price = new BigDecimal(i % 2 == 0 ? "100" : "101");
      Instant t = T0.plusSeconds(3600L * i);
      out.add(new Candle(t, price, price, price, price, new BigDecimal("1000000")));
    }
    return out;
  }

  private static List<FundingEvent> fundingEvery(int bars, int stepBars) {
    List<FundingEvent> out = new ArrayList<>();
    for (int i = 0; i < bars; i += stepBars) {
      out.add(new FundingEvent(T0.plusSeconds(3600L * i), new BigDecimal("0.001")));
    }
    return out;
  }
}
