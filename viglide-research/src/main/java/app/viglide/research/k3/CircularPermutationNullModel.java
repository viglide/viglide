package app.viglide.research.k3;

import java.util.Random;
import java.util.function.ToDoubleBiFunction;

/**
 * Generalizes {@code NullModelCli}'s circular-permutation null model (public {@code
 * app.viglide.research.cli}, funding-rate-specific) to any paired-series statistic — S3's "existing
 * machinery" (ADR-0028), lifted so K3 can null-test a rank IC over a forward-return series instead
 * of a total-return statistic over a permuted funding-rate series. The permutation and
 * tie-corrected-percentile math mirror {@code NullModelCli.circularPermute}/{@code percentileOf}
 * exactly; duplicated here rather than shared because both are package-private in a CLI class that
 * is not a library entry point.
 */
public final class CircularPermutationNullModel {

  private CircularPermutationNullModel() {}

  /**
   * One null-model run's outcome: where the real statistic falls within its own null distribution.
   */
  public record NullModelResult(
      double realStatistic,
      double[] nullStatistics,
      double percentile,
      int countStrictlyBelow,
      int countEqual) {

    /**
     * Whether the real statistic clears the 95th-percentile bar (ADR-0016 condition 6 / ADR-0028
     * S3).
     */
    public boolean beatsP95() {
      return percentile >= 0.95;
    }
  }

  /**
   * Rotates {@code y} circularly by {@code offset} positions (wrap-around) while leaving {@code x}
   * fixed — preserves {@code y}'s own autocorrelation and marginal distribution while destroying
   * its alignment with {@code x}, exactly {@code NullModelCli.circularPermute}'s rationale applied
   * to a generic {@code double[]} instead of a {@code List<FundingEvent>}.
   */
  static double[] circularPermute(double[] y, int offset) {
    int n = y.length;
    double[] out = new double[n];
    for (int i = 0; i < n; i++) {
      out[i] = y[Math.floorMod(i + offset, n)];
    }
    return out;
  }

  /**
   * Runs {@code n} circular permutations of {@code y} against fixed {@code x}, recomputing {@code
   * statistic} on each permuted pairing, and reports where {@code statistic(x, y)} itself falls
   * within that null distribution.
   *
   * @param statistic e.g. {@link RankIc#spearman}
   * @param n permutation count (ADR-0028 S3: 200)
   * @param seed makes the permutation offsets reproducible (NFR-7)
   */
  public static NullModelResult run(
      double[] x, double[] y, ToDoubleBiFunction<double[], double[]> statistic, int n, long seed) {
    if (x.length != y.length) {
      throw new IllegalArgumentException(
          "arrays must be the same length, got " + x.length + " and " + y.length);
    }
    double realStatistic = statistic.applyAsDouble(x, y);
    Random rng = new Random(seed);
    double[] nullStatistics = new double[n];
    for (int i = 0; i < n; i++) {
      int offset = y.length == 0 ? 0 : rng.nextInt(Math.max(1, y.length));
      double[] permutedY = circularPermute(y, offset);
      nullStatistics[i] = statistic.applyAsDouble(x, permutedY);
    }
    return percentileOf(nullStatistics, realStatistic);
  }

  /**
   * Tie-corrected percentile of {@code realStatistic} within {@code nullStatistics} (mid-rank
   * rule).
   */
  static NullModelResult percentileOf(double[] nullStatistics, double realStatistic) {
    int countStrictlyBelow = 0;
    int countEqual = 0;
    for (double v : nullStatistics) {
      if (v < realStatistic) {
        countStrictlyBelow++;
      } else if (v == realStatistic) {
        countEqual++;
      }
    }
    int n = nullStatistics.length;
    double percentile = n == 0 ? 0.5 : (countStrictlyBelow + 0.5 * countEqual) / n;
    return new NullModelResult(
        realStatistic, nullStatistics, percentile, countStrictlyBelow, countEqual);
  }
}
