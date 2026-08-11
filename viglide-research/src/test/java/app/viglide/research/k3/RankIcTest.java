package app.viglide.research.k3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link RankIc}. */
class RankIcTest {

  @Test
  void identicalSeriesCorrelatePerfectly() {
    double[] x = {1.0, 2.0, 3.0, 4.0, 5.0};
    assertThat(RankIc.spearman(x, x)).isCloseTo(1.0, within(1e-9));
  }

  @Test
  void reversedSeriesCorrelateNegativelyPerfectly() {
    double[] x = {1.0, 2.0, 3.0, 4.0, 5.0};
    double[] y = {5.0, 4.0, 3.0, 2.0, 1.0};
    assertThat(RankIc.spearman(x, y)).isCloseTo(-1.0, within(1e-9));
  }

  @Test
  void monotonicNonLinearRelationshipStillCorrelatesPerfectly() {
    // Spearman is rank-based -- a monotonic but non-linear relationship is still rank-perfect.
    double[] x = {1.0, 2.0, 3.0, 4.0, 5.0};
    double[] y = {1.0, 4.0, 9.0, 16.0, 25.0};
    assertThat(RankIc.spearman(x, y)).isCloseTo(1.0, within(1e-9));
  }

  @Test
  void tiesAreHandledByAverageRank() {
    double[] x = {1.0, 1.0, 2.0, 3.0};
    double[] y = {1.0, 1.0, 2.0, 3.0};
    assertThat(RankIc.spearman(x, y)).isCloseTo(1.0, within(1e-9));
  }

  @Test
  void constantSeriesReturnsZero_notNaN() {
    double[] x = {1.0, 1.0, 1.0, 1.0};
    double[] y = {1.0, 2.0, 3.0, 4.0};
    assertThat(RankIc.spearman(x, y)).isEqualTo(0.0);
  }

  @Test
  void rejectsMismatchedLengths() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> RankIc.spearman(new double[] {1, 2}, new double[] {1, 2, 3}));
  }

  @Test
  void rejectsFewerThanTwoObservations() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> RankIc.spearman(new double[] {1}, new double[] {1}));
  }
}
