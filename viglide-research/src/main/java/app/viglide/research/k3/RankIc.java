package app.viglide.research.k3;

import java.util.Arrays;

/**
 * Spearman rank correlation — S1's predictive-content statistic (ADR-0028): the rank correlation
 * between a signal's stated probability and the forward return it predicted, so a strategy that
 * merely orders pairs correctly still scores well even before its probability scale is known to be
 * well-calibrated (S2 measures that separately).
 */
public final class RankIc {

  private RankIc() {}

  /**
   * @throws IllegalArgumentException if the arrays differ in length or have fewer than 2 elements —
   *     a correlation needs at least 2 points, and 2 points always correlate perfectly, so a caller
   *     needing a meaningful statistic must enforce a much higher floor itself (see S6)
   */
  public static double spearman(double[] x, double[] y) {
    if (x.length != y.length) {
      throw new IllegalArgumentException(
          "arrays must be the same length, got " + x.length + " and " + y.length);
    }
    if (x.length < 2) {
      throw new IllegalArgumentException("need at least 2 observations, got " + x.length);
    }
    return pearson(rank(x), rank(y));
  }

  /** Fractional (mid-)ranks, ties averaged — the standard Spearman tie-handling rule. */
  private static double[] rank(double[] values) {
    int n = values.length;
    Integer[] order = new Integer[n];
    for (int i = 0; i < n; i++) {
      order[i] = i;
    }
    Arrays.sort(order, (a, b) -> Double.compare(values[a], values[b]));

    double[] ranks = new double[n];
    int i = 0;
    while (i < n) {
      int j = i;
      while (j + 1 < n && values[order[j + 1]] == values[order[i]]) {
        j++;
      }
      double avgRank = (i + j) / 2.0 + 1.0; // 1-based
      for (int k = i; k <= j; k++) {
        ranks[order[k]] = avgRank;
      }
      i = j + 1;
    }
    return ranks;
  }

  private static double pearson(double[] x, double[] y) {
    int n = x.length;
    double meanX = mean(x);
    double meanY = mean(y);
    double cov = 0.0;
    double varX = 0.0;
    double varY = 0.0;
    for (int i = 0; i < n; i++) {
      double dx = x[i] - meanX;
      double dy = y[i] - meanY;
      cov += dx * dy;
      varX += dx * dx;
      varY += dy * dy;
    }
    if (varX == 0.0 || varY == 0.0) {
      return 0.0; // degenerate (constant series) -- no correlation is expressible, not an error
    }
    return cov / Math.sqrt(varX * varY);
  }

  private static double mean(double[] values) {
    double sum = 0.0;
    for (double v : values) {
      sum += v;
    }
    return sum / values.length;
  }
}
