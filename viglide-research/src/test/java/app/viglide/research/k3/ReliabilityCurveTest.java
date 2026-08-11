package app.viglide.research.k3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ReliabilityCurve}. */
class ReliabilityCurveTest {

  @Test
  void perfectlyCalibratedSignalHasNearZeroCalibrationError() {
    // 100 observations at p=0.9, exactly 90 correct -- the textbook well-calibrated case.
    double[] predicted = new double[100];
    boolean[] outcomes = new boolean[100];
    for (int i = 0; i < 100; i++) {
      predicted[i] = 0.9;
      outcomes[i] = i < 90;
    }
    List<ReliabilityCurve.Bin> curve = ReliabilityCurve.compute(predicted, outcomes, 10);
    double ece = ReliabilityCurve.expectedCalibrationError(curve);
    assertThat(ece).isCloseTo(0.0, within(1e-9));
  }

  @Test
  void overconfidentSignalHasLargeCalibrationError() {
    // Claims 0.9 every time but is only right half the time -- badly overconfident.
    double[] predicted = new double[100];
    boolean[] outcomes = new boolean[100];
    for (int i = 0; i < 100; i++) {
      predicted[i] = 0.9;
      outcomes[i] = i < 50;
    }
    List<ReliabilityCurve.Bin> curve = ReliabilityCurve.compute(predicted, outcomes, 10);
    double ece = ReliabilityCurve.expectedCalibrationError(curve);
    assertThat(ece).isCloseTo(0.4, within(1e-9));
  }

  @Test
  void emptyBinsAreOmitted() {
    double[] predicted = {0.05, 0.05, 0.95, 0.95};
    boolean[] outcomes = {true, false, true, true};
    List<ReliabilityCurve.Bin> curve = ReliabilityCurve.compute(predicted, outcomes, 10);
    // Only the [0.0,0.1) and [0.9,1.0) bins have observations.
    assertThat(curve).hasSize(2);
  }

  @Test
  void boundaryProbabilityOneLandsInLastBin_notAPhantomExtraBin() {
    double[] predicted = {1.0};
    boolean[] outcomes = {true};
    List<ReliabilityCurve.Bin> curve = ReliabilityCurve.compute(predicted, outcomes, 10);
    assertThat(curve).hasSize(1);
    assertThat(curve.get(0).upperBound()).isCloseTo(1.0, within(1e-9));
  }

  @Test
  void rejectsMismatchedLengths() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> ReliabilityCurve.compute(new double[] {0.5}, new boolean[] {true, false}, 5));
  }

  @Test
  void rejectsZeroBins() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ReliabilityCurve.compute(new double[] {0.5}, new boolean[] {true}, 0));
  }
}
