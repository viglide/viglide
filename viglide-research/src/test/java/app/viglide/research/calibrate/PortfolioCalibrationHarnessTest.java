package app.viglide.research.calibrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.FeeModel;
import app.viglide.core.calibrate.PortfolioCandidate;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.domain.PortfolioContext;
import app.viglide.core.domain.PositionShape;
import app.viglide.core.domain.TargetPosition;
import app.viglide.core.risk.RiskParameters;
import app.viglide.core.spi.PortfolioStrategy;
import app.viglide.core.spi.StrategyMetadata;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
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

  /**
   * Constant carry direction, but the weight itself alternates 1.0 / 0.8 every bar -- the position
   * never goes flat, so every alternation is a same-direction resize whose delta against {@code
   * lastDesired} is entirely governed by {@code noTradeBand} (PLAN-021 Task B), unlike {@link
   * TogglingWeightStrategy}'s open/flat/open pattern above.
   */
  private record OscillatingWeightStrategy(String symbol) implements PortfolioStrategy {
    @Override
    public List<TargetPosition> evaluate(PortfolioContext context) {
      if (!context.bySymbol().containsKey(symbol)) {
        return List.of();
      }
      long hour = context.asOf().getEpochSecond() / 3600L;
      BigDecimal weight = hour % 2 == 0 ? BigDecimal.ONE : new BigDecimal("0.8");
      return List.of(
          new TargetPosition(
              symbol, weight, PositionShape.DELTA_NEUTRAL_CARRY, List.of(), "oscillate"));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("oscillating-weight-test", "1.0", "test-only");
    }
  }

  /** Constant full-weight carry target -- used for PLAN-021 Task C's leverage/margin fixture. */
  private record ConstantCarryStrategy(String symbol) implements PortfolioStrategy {
    @Override
    public List<TargetPosition> evaluate(PortfolioContext context) {
      if (!context.bySymbol().containsKey(symbol)) {
        return List.of();
      }
      return List.of(
          new TargetPosition(
              symbol, BigDecimal.ONE, PositionShape.DELTA_NEUTRAL_CARRY, List.of(), "constant"));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("constant-carry-leverage-test", "1.0", "test-only");
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

  @Test
  void run_perCandidateNoTradeBand_producesDifferentTradeCounts() {
    // PLAN-021 Task B (docs/tasks/PLAN-021-plan019-review-findings.md, Task B): before this field
    // existed, PortfolioCandidate had no way to vary noTradeBand, so this is the one assertion the
    // pre-fix API could not make -- two candidates, identical except for the band, diverging.
    String symbol = "AAA";
    List<Candle> perp = alternatingCandles(80);
    List<Candle> spot = alternatingCandles(80);
    Map<String, List<FundingEvent>> funding = Map.of(symbol, fundingEvery(80, 2));
    BacktestConfig cfg = smallCapCfg();
    PortfolioStrategy strategy = new OscillatingWeightStrategy(symbol);

    // Every bar, desired size swings by |1.0 - 0.8| * $10,000 = $2,000.
    PortfolioCandidate tightBand =
        new PortfolioCandidate(
            strategy, Map.of("variant", "tight"), UnaryOperator.identity(), new BigDecimal("0.05"));
    PortfolioCandidate wideBand =
        new PortfolioCandidate(
            strategy, Map.of("variant", "wide"), UnaryOperator.identity(), new BigDecimal("0.30"));

    List<PortfolioCalibrationResult> results =
        PortfolioCalibrationHarness.run(
            Map.of(symbol, perp),
            funding,
            Map.of(symbol, spot),
            2,
            0,
            0,
            CandleInterval.ONE_HOUR,
            cfg,
            new BigDecimal("10000"),
            new BigDecimal("0.5"), // harness-wide default -- both candidates override it
            List.of(tightBand, wideBand),
            PortfolioScoringFunction.CARRY_YIELD);

    assertThat(results).hasSize(2);
    PortfolioCalibrationResult tight =
        results.stream()
            .filter(r -> "tight".equals(r.params().get("variant")))
            .findFirst()
            .orElseThrow();
    PortfolioCalibrationResult wide =
        results.stream()
            .filter(r -> "wide".equals(r.params().get("variant")))
            .findFirst()
            .orElseThrow();

    // 0.05 * $10,000 = $500 < $2,000 delta -> rebalances every alternation. 0.30 * $10,000 = $3,000
    // > $2,000 delta -> the band swallows every alternation, leaving only the opening trade, which
    // never closes voluntarily and is excluded as an END_OF_DATA artifact.
    assertThat(tight.cvTradeCountTotal()).isGreaterThan(wide.cvTradeCountTotal());
    assertThat(wide.cvTradeCountTotal()).isEqualTo(0);

    // The winning row is self-describing: the effective band is echoed into params even though
    // neither candidate's own paramsSnapshot mentioned it.
    assertThat(tight.params().get("noTradeBand")).isEqualTo(new BigDecimal("0.05"));
    assertThat(wide.params().get("noTradeBand")).isEqualTo(new BigDecimal("0.30"));
  }

  @Test
  void run_reservedParamKeys_reportEffectiveValues_notCallerSuppliedOnes() {
    // The reserved keys must describe the run, not the caller's intent. A grid builder that writes
    // the band into paramsSnapshot but forgets PortfolioCandidate#noTradeBand is the realistic
    // mistake here: honouring its label would emit a row claiming 0.05 for a run that used the
    // harness-wide 0.5, re-creating PLAN-021 Task B's silent collapse in the audit trail itself.
    String symbol = "AAA";
    List<Candle> perp = alternatingCandles(80);
    PortfolioCandidate mislabelled =
        new PortfolioCandidate(
            new OscillatingWeightStrategy(symbol),
            Map.of("variant", "mislabelled", "noTradeBand", "0.05", "maxLeverage", "99"));

    List<PortfolioCalibrationResult> results =
        PortfolioCalibrationHarness.run(
            Map.of(symbol, perp),
            Map.of(symbol, fundingEvery(80, 2)),
            Map.of(symbol, alternatingCandles(80)),
            2,
            0,
            0,
            CandleInterval.ONE_HOUR,
            smallCapCfg(),
            new BigDecimal("10000"),
            new BigDecimal("0.5"),
            List.of(mislabelled),
            PortfolioScoringFunction.CARRY_YIELD);

    assertThat(results).hasSize(1);
    Map<String, Object> params = results.get(0).params();
    assertThat(params.get("noTradeBand")).isEqualTo(new BigDecimal("0.5"));
    assertThat(params.get("maxLeverage")).isEqualTo(RiskParameters.defaults().maxLeverage());
    // Non-reserved caller keys are untouched.
    assertThat(params.get("variant")).isEqualTo("mislabelled");
  }

  @Test
  void portfolioCandidate_rejectsNegativeNoTradeBand() {
    // runTargets enforces this bound too, but only once a fold actually runs -- by which point a
    // malformed grid has already been accepted and partially evaluated.
    assertThatThrownBy(
            () ->
                new PortfolioCandidate(
                    new ConstantCarryStrategy("AAA"),
                    Map.of("variant", "bad"),
                    UnaryOperator.identity(),
                    new BigDecimal("-0.01")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("noTradeBand");
  }

  @Test
  void run_riskParametersOverload_differentLeverage_producesDifferentLiquidationOutcome() {
    // PLAN-021 Task C (docs/tasks/PLAN-021-plan019-review-findings.md, Task C): leverage sets the
    // carry margin divisor (PortfolioFundingArbHarnessV2's margin = q * price / leverage), so it
    // directly controls whether this ramp fixture liquidates. Same candidate, same fixture, only
    // RiskParameters#maxLeverage differs between the two calls.
    String symbol = "AAA";
    List<Candle> perp = rampToLiquidationCandles();
    List<Candle> spot = rampToLiquidationCandles();
    BacktestConfig cfg = smallCapCfg();
    List<PortfolioCandidate> candidates =
        List.of(
            new PortfolioCandidate(new ConstantCarryStrategy(symbol), Map.of("variant", "ramp")));

    RiskParameters twoX = RiskParameters.defaults(); // 2.0x -- this ramp liquidates under it
    RiskParameters oneX = withLeverage(twoX, new BigDecimal("1.0")); // never liquidates

    List<PortfolioCalibrationResult> at2x =
        PortfolioCalibrationHarness.run(
            Map.of(symbol, perp),
            Map.of(),
            Map.of(symbol, spot),
            2,
            0,
            0,
            CandleInterval.ONE_HOUR,
            cfg,
            new BigDecimal("10000"),
            new BigDecimal("0.05"),
            candidates,
            PortfolioScoringFunction.CARRY_YIELD,
            twoX);
    List<PortfolioCalibrationResult> at1x =
        PortfolioCalibrationHarness.run(
            Map.of(symbol, perp),
            Map.of(),
            Map.of(symbol, spot),
            2,
            0,
            0,
            CandleInterval.ONE_HOUR,
            cfg,
            new BigDecimal("10000"),
            new BigDecimal("0.05"),
            candidates,
            PortfolioScoringFunction.CARRY_YIELD,
            oneX);

    assertThat(at2x).hasSize(1);
    assertThat(at1x).hasSize(1);
    // At 2x: q=2 (RM-approved $200 / $100 entry), margin=2*100/2=100, threshold=90 -> trips at
    // close=150 (delta 50 * q=2 = 100 >= 90), the ramp's last bar -- one LIQUIDATION_GUARD trade.
    // At 1x: margin=2*100/1=200, threshold=180 -> needs close>=190; the ramp only reaches 150, so
    // the position opens once and is held (Task A's fix: no re-churn on price movement alone) until
    // it is excluded as an END_OF_DATA artifact at each fold boundary -- zero counted trades.
    assertThat(at2x.get(0).cvTradeCountTotal()).isGreaterThan(at1x.get(0).cvTradeCountTotal());
    assertThat(at1x.get(0).cvTradeCountTotal()).isEqualTo(0);

    // The effective leverage that shaped the run is auditable from the output alone.
    assertThat(at2x.get(0).params().get("maxLeverage")).isEqualTo(new BigDecimal("2.0"));
    assertThat(at1x.get(0).params().get("maxLeverage")).isEqualTo(new BigDecimal("1.0"));
  }

  private static RiskParameters withLeverage(RiskParameters base, BigDecimal maxLeverage) {
    return new RiskParameters(
        base.maxPositionPct(),
        base.maxPortfolioDrawdownPct(),
        maxLeverage,
        base.maxDailyVolumePct(),
        base.maxPortfolioRiskPct(),
        base.stopLossAtrMult(),
        base.confidenceFloor(),
        base.maxStaleInputAge(),
        base.maxTotalDeployedAbs(),
        base.maxPositionAbs(),
        base.maxDailyLossAbs(),
        base.maxCampaignLossAbs());
  }

  /**
   * 25 bars alternating 100/101 (real ATR, needed for the real RiskManager to approve the opening
   * BUY at all -- a run of flat identical closes gives ATR(14)==0 and every open is refused as
   * STOP_LOSS_UNDERIVABLE, delaying entry until the ramp itself and invalidating the margin math
   * below), then a 5-bar ramp 110/120/130/140/150 landing exactly on fold 0's last bar (folds=2
   * over 60 bars -> chunk size 30), then 30 more flat bars @ 150 filling fold 1 with nothing left
   * for either leverage setting to react to.
   */
  private static List<Candle> rampToLiquidationCandles() {
    List<Candle> out = new ArrayList<>(60);
    for (int i = 0; i < 25; i++) {
      out.add(rampCandle(i, i % 2 == 0 ? 100 : 101));
    }
    double[] ramp = {110, 120, 130, 140, 150};
    for (int i = 0; i < ramp.length; i++) {
      out.add(rampCandle(25 + i, ramp[i]));
    }
    for (int i = 0; i < 30; i++) {
      out.add(rampCandle(30 + i, 150));
    }
    return out;
  }

  private static Candle rampCandle(int hourIndex, double price) {
    BigDecimal p = BigDecimal.valueOf(price);
    Instant t = T0.plusSeconds(3600L * hourIndex);
    // Generous volume -- the RM's daily-volume cap must not interfere with this margin/leverage
    // fixture, same convention as PortfolioBacktestHarnessRunTargetsCarryTest's own real-RM test.
    return new Candle(t, p, p, p, p, new BigDecimal("1000000"));
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
