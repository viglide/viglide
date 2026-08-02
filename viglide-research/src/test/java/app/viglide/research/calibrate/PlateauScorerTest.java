package app.viglide.research.calibrate;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.FeeModel;
import app.viglide.core.data.CsvKlineReader;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit + integration tests for {@link PlateauScorer} (PLAN-009 Task E). */
class PlateauScorerTest {

  // ── perturbations(): pure, type-driven neighbor generation ─────────────────────────────────

  @Test
  void perturbations_intParam_generatesPlusMinusOneStep() {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("period", 10);
    List<Map<String, Object>> neighbors = PlateauScorer.perturbations(params);
    assertThat(neighbors).hasSize(2);
    assertThat(neighbors).extracting(m -> m.get("period")).containsExactlyInAnyOrder(9, 11);
  }

  @Test
  void perturbations_doubleParam_generatesFourPercentageSteps() {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("threshold", 100.0);
    List<Map<String, Object>> neighbors = PlateauScorer.perturbations(params);
    assertThat(neighbors).hasSize(4);
    List<Double> values =
        neighbors.stream().map(m -> (Double) m.get("threshold")).sorted().toList();
    org.assertj.core.data.Offset<Double> tol = org.assertj.core.data.Offset.offset(1e-9);
    assertThat(values.get(0)).isCloseTo(80.0, tol);
    assertThat(values.get(1)).isCloseTo(90.0, tol);
    assertThat(values.get(2)).isCloseTo(110.0, tol);
    assertThat(values.get(3)).isCloseTo(120.0, tol);
  }

  @Test
  void perturbations_zeroValuedDoubleParam_usesAbsoluteStepNotPercentageScaling() {
    // d * factor is a no-op at d=0.0 for every factor -- fundingarb's exitThreshold=0.0 ("exit
    // when funding crosses zero") is a real, common value this must not silently ignore.
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("exitThreshold", 0.0);
    List<Map<String, Object>> neighbors = PlateauScorer.perturbations(params);
    assertThat(neighbors).hasSize(2);
    List<Double> values =
        neighbors.stream().map(m -> (Double) m.get("exitThreshold")).sorted().toList();
    assertThat(values.get(0)).isNotEqualTo(0.0);
    assertThat(values.get(1)).isNotEqualTo(0.0);
    assertThat(values.get(0)).isNegative();
    assertThat(values.get(1)).isPositive();
  }

  @Test
  void perturbations_stringAndBooleanParams_areNotPerturbed() {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("mode", "aggressive");
    params.put("enabled", true);
    assertThat(PlateauScorer.perturbations(params)).isEmpty();
  }

  @Test
  void perturbations_perturbOneParamAtATime_othersStayAtOriginalValue() {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("a", 10);
    params.put("b", 20.0);
    List<Map<String, Object>> neighbors = PlateauScorer.perturbations(params);
    assertThat(neighbors).hasSize(6); // 2 (int a) + 4 (double b)
    for (Map<String, Object> n : neighbors) {
      boolean aChanged = !n.get("a").equals(10);
      boolean bChanged = !n.get("b").equals(20.0);
      assertThat(aChanged ^ bChanged)
          .as("exactly one param differs from the original: %s", n)
          .isTrue();
    }
  }

  @Test
  void perturbations_mixedTypes_totalCountIsSumPerParam() {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("intA", 5);
    params.put("intB", 7);
    params.put("doubleA", 1.0);
    params.put("category", "x");
    assertThat(PlateauScorer.perturbations(params)).hasSize(2 + 2 + 4); // intA, intB, doubleA
  }

  // ── plateauScore(): integration with a real strategy + fold evaluation ─────────────────────

  @Test
  void plateauScore_returnsFiniteValueForARealCandidate() throws Exception {
    Path dataset = Path.of("src/test/resources/fixtures/large_snippets/BTCUSDT_1h_month.csv");
    List<Candle> candles;
    try (var stream = CsvKlineReader.stream(dataset.toAbsolutePath())) {
      candles = stream.toList();
    }
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.binanceDefault(),
            100,
            new BigDecimal("0.02"),
            new BigDecimal("0.02"),
            new BigDecimal("0.04"),
            8760);
    List<FoldSplitter.FoldWindow> chunks =
        FoldSplitter.splitWithPrefix(candles, 3, cfg.warmupBars());

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("emaFast", 9);
    params.put("emaSlow", 21);
    params.put("rsiPeriod", 14);
    params.put("rsiOverbought", 70.0);
    params.put("rsiOversold", 30.0);
    params.put("spreadScale", 0.01);
    CalibrationResult original =
        new CalibrationResult(
            params, 0.5, BigDecimal.ZERO, BigDecimal.ZERO, 15, 3, 45, BigDecimal.ZERO, 0.0);

    double score =
        PlateauScorer.plateauScore(
            "emarsi",
            original,
            chunks,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            cfg,
            FoldRunner.defaultOhlcv());

    assertThat(score).isFinite();
  }

  @Test
  void plateauScore_fundingArbMinHoldBars_doesNotCrashAndIsReDerivedPerNeighbor() throws Exception {
    // minHoldBars rides along in the params snapshot as a harness knob, not a strategy
    // constructor arg (FundingArbParameterSpace) -- confirms configOverrideFor() handles it
    // without CalibrationResult needing to retain the original Candidate's configOverride.
    Path dataset = Path.of("src/test/resources/fixtures/large_snippets/BTCUSDT_1h_month.csv");
    List<Candle> perp;
    try (var stream = CsvKlineReader.stream(dataset.toAbsolutePath())) {
      perp = stream.toList();
    }
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.binanceDefault(),
            50,
            new BigDecimal("0.5"),
            null,
            null,
            8760);
    List<FoldSplitter.FoldWindow> chunks = FoldSplitter.splitWithPrefix(perp, 3, cfg.warmupBars());

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("windowSize", 2);
    params.put("entryThreshold", 3.0e-05);
    params.put("exitThreshold", -2.0e-05);
    params.put("confidenceScale", 5.0e-04);
    params.put("minFundingEvents", 6);
    params.put("exitConsecutive", 1);
    params.put("minHoldBars", 8);
    CalibrationResult original =
        new CalibrationResult(
            params, -1.0, BigDecimal.ZERO, BigDecimal.ZERO, 10, 3, 30, BigDecimal.ZERO, 0.0);

    // No funding events / no spot leg -- the default OHLCV fold runner is fine here, this test is
    // only checking that minHoldBars perturbation doesn't throw, not fundingarb's real economics.
    double score =
        PlateauScorer.plateauScore(
            "fundingarb",
            original,
            chunks,
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            cfg,
            FoldRunner.withFunding(List.of()));

    assertThat(score).isFinite();
  }
}
