package app.viglide.research.k3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link BrierDecomposition}. */
class BrierDecompositionTest {

  @Test
  void perfectPredictionsHaveZeroBrierScore() {
    double[] predicted = {1.0, 1.0, 0.0, 0.0};
    boolean[] outcomes = {true, true, false, false};
    BrierDecomposition.Result result = BrierDecomposition.compute(predicted, outcomes, 4);
    assertThat(result.brierScore()).isCloseTo(0.0, within(1e-9));
    assertThat(result.reliability()).isCloseTo(0.0, within(1e-9));
  }

  @Test
  void alwaysWrongPredictionsHaveMaximalBrierScore() {
    double[] predicted = {1.0, 1.0, 0.0, 0.0};
    boolean[] outcomes = {false, false, true, true};
    BrierDecomposition.Result result = BrierDecomposition.compute(predicted, outcomes, 4);
    assertThat(result.brierScore()).isCloseTo(1.0, within(1e-9));
  }

  @Test
  void uninformativeConstantPredictionHasZeroResolution() {
    // Always predicts the base rate -- perfectly calibrated but resolves nothing.
    double[] predicted = {0.5, 0.5, 0.5, 0.5};
    boolean[] outcomes = {true, false, true, false};
    BrierDecomposition.Result result = BrierDecomposition.compute(predicted, outcomes, 2);
    assertThat(result.reliability()).isCloseTo(0.0, within(1e-9));
    assertThat(result.resolution()).isCloseTo(0.0, within(1e-9));
    assertThat(result.uncertainty()).isCloseTo(0.25, within(1e-9));
  }

  @Test
  void componentsAreNonNegative() {
    double[] predicted = {0.1, 0.4, 0.6, 0.9, 0.3, 0.7};
    boolean[] outcomes = {false, true, false, true, true, false};
    BrierDecomposition.Result result = BrierDecomposition.compute(predicted, outcomes, 5);
    assertThat(result.brierScore()).isGreaterThanOrEqualTo(0.0);
    assertThat(result.reliability()).isGreaterThanOrEqualTo(0.0);
    assertThat(result.resolution()).isGreaterThanOrEqualTo(0.0);
    assertThat(result.uncertainty()).isGreaterThanOrEqualTo(0.0);
  }

  @Test
  void emptyInputReturnsAllZeros() {
    BrierDecomposition.Result result = BrierDecomposition.compute(new double[0], new boolean[0], 5);
    assertThat(result.brierScore()).isEqualTo(0.0);
    assertThat(result.reliability()).isEqualTo(0.0);
    assertThat(result.resolution()).isEqualTo(0.0);
    assertThat(result.uncertainty()).isEqualTo(0.0);
  }
}
