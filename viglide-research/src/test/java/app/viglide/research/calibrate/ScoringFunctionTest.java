package app.viglide.research.calibrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ScoringFunction} (PLAN-013 Task D, review finding F4). */
class ScoringFunctionTest {

  @Test
  void byName_resolvesBothObjectives() {
    assertThat(ScoringFunction.byName("median-cv-sharpe"))
        .isSameAs(ScoringFunction.MEDIAN_CV_SHARPE);
    assertThat(ScoringFunction.byName("carry-yield")).isSameAs(ScoringFunction.CARRY_YIELD);
  }

  @Test
  void byName_unknownName_throwsListingBothValidNames() {
    assertThatThrownBy(() -> ScoringFunction.byName("sharpe"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("median-cv-sharpe")
        .hasMessageContaining("carry-yield");
  }

  @Test
  void medianCvSharpe_ranksPurelyByCvSharpeMedian_ignoringEconomicMetrics() {
    CalibrationResult highSharpeLowYield =
        result(10.0, "0.001", 0.5); // idle-diluted Sharpe artifact: high Sharpe, thin economics
    CalibrationResult lowSharpeHighYield =
        result(1.0, "0.30", 1.0); // modest Sharpe, real pooled yield

    assertThat(ScoringFunction.MEDIAN_CV_SHARPE.score(highSharpeLowYield))
        .isGreaterThan(ScoringFunction.MEDIAN_CV_SHARPE.score(lowSharpeHighYield));
  }

  @Test
  void carryYield_picksADifferentAndExplainableWinner_thanMedianCvSharpe() {
    // PLAN-013 Task D acceptance criterion, verbatim: "--objective=carry-yield on the same data
    // produces a different and explainable winner". Candidate A is the idle-diluted-Sharpe
    // artifact Task A/PLAN-013 §0 exists to correct for: a high cvSharpeMedian earned on a thin
    // pooled economic return. Candidate B has a lower cvSharpeMedian but a real pooled yield and a
    // shallower drawdown profile (lower ulcerIndex). MEDIAN_CV_SHARPE and CARRY_YIELD must
    // disagree on which of these is "better" -- that disagreement is the whole point of Task D.
    CalibrationResult candidateA = result(10.0, "0.001", 0.5); // ulcerIndex=0.5 (some drawdown)
    CalibrationResult candidateB = result(1.0, "0.30", 0.1); // ulcerIndex=0.1 (shallow drawdown)

    boolean medianSharpePicksA =
        ScoringFunction.MEDIAN_CV_SHARPE.score(candidateA)
            > ScoringFunction.MEDIAN_CV_SHARPE.score(candidateB);
    boolean carryYieldPicksA =
        ScoringFunction.CARRY_YIELD.score(candidateA)
            > ScoringFunction.CARRY_YIELD.score(candidateB);

    assertThat(medianSharpePicksA).isTrue(); // A's inflated Sharpe wins under the old objective
    assertThat(carryYieldPicksA)
        .isFalse(); // B's real, shallower-drawdown yield wins under the new one
  }

  @Test
  void carryYield_zeroUlcerIndex_doesNotDivideByZero() {
    CalibrationResult perfectlyFlat = result(0.0, "0.05", 0.0);
    assertThat(ScoringFunction.CARRY_YIELD.score(perfectlyFlat)).isEqualTo(0.05); // /(1+0) = itself
  }

  private static CalibrationResult result(
      double cvSharpeMedian, String pooledReturnOnDeployedCapital, double ulcerIndexMedian) {
    return new CalibrationResult(
        Map.of("test", "fixture"),
        cvSharpeMedian,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        5,
        3,
        30,
        new BigDecimal(pooledReturnOnDeployedCapital),
        ulcerIndexMedian);
  }
}
