package app.viglide.research.k3;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CircularPermutationNullModel}. */
class CircularPermutationNullModelTest {

  @Test
  void circularPermuteRotatesWithWrapAround() {
    double[] y = {10.0, 20.0, 30.0, 40.0};
    double[] rotated = CircularPermutationNullModel.circularPermute(y, 1);
    assertThat(rotated).containsExactly(20.0, 30.0, 40.0, 10.0);
  }

  @Test
  void circularPermuteWithZeroOffsetIsIdentity() {
    double[] y = {10.0, 20.0, 30.0};
    assertThat(CircularPermutationNullModel.circularPermute(y, 0)).containsExactly(y);
  }

  @Test
  void aStrongRealRelationshipBeatsARandomNull() {
    // x and y perfectly rank-correlated -- every circular rotation of y (other than the identity)
    // destroys that alignment, so the real statistic should sit at or near the top of its own null
    // distribution.
    int n = 40;
    double[] x = new double[n];
    double[] y = new double[n];
    for (int i = 0; i < n; i++) {
      x[i] = i;
      y[i] = i * 2.0;
    }
    CircularPermutationNullModel.NullModelResult result =
        CircularPermutationNullModel.run(x, y, RankIc::spearman, 200, 42L);
    assertThat(result.realStatistic()).isCloseTo(1.0, org.assertj.core.api.Assertions.within(1e-9));
    assertThat(result.percentile()).isGreaterThanOrEqualTo(0.95);
    assertThat(result.beatsP95()).isTrue();
  }

  @Test
  void unrelatedSeriesTypicallyDoesNotBeatP95() {
    Random dataRng = new Random(7);
    int n = 60;
    double[] x = new double[n];
    double[] y = new double[n];
    for (int i = 0; i < n; i++) {
      x[i] = dataRng.nextDouble();
      y[i] = dataRng.nextDouble();
    }
    CircularPermutationNullModel.NullModelResult result =
        CircularPermutationNullModel.run(x, y, RankIc::spearman, 200, 99L);
    // Not a hard guarantee for any single unrelated draw, but comfortably true for this fixed
    // seed pair -- documents the expected shape (most of the null mass, not the tail) rather than
    // asserting a specific percentile.
    assertThat(result.percentile()).isLessThan(0.95);
  }

  @Test
  void percentileOfHandlesTiesWithMidRank() {
    double[] nullStats = {1.0, 2.0, 3.0, 3.0, 4.0};
    CircularPermutationNullModel.NullModelResult result =
        CircularPermutationNullModel.percentileOf(nullStats, 3.0);
    assertThat(result.countStrictlyBelow()).isEqualTo(2);
    assertThat(result.countEqual()).isEqualTo(2);
    assertThat(result.percentile()).isEqualTo((2 + 0.5 * 2) / 5.0);
  }

  @Test
  void resultIsReproducibleForTheSameSeed() {
    double[] x = {1, 2, 3, 4, 5, 6, 7, 8};
    double[] y = {2, 1, 4, 3, 6, 5, 8, 7};
    CircularPermutationNullModel.NullModelResult a =
        CircularPermutationNullModel.run(x, y, RankIc::spearman, 50, 123L);
    CircularPermutationNullModel.NullModelResult b =
        CircularPermutationNullModel.run(x, y, RankIc::spearman, 50, 123L);
    assertThat(a.nullStatistics()).containsExactly(b.nullStatistics());
    assertThat(a.percentile()).isEqualTo(b.percentile());
  }
}
