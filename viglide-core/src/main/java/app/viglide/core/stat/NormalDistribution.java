package app.viglide.core.stat;

/**
 * Standard normal distribution CDF and inverse-CDF, pure Java with zero dependencies (PLAN-008 Task
 * C.4) — needed by the Probabilistic/Deflated Sharpe Ratio calculations in {@link
 * app.viglide.core.backtest.Metrics}.
 */
public final class NormalDistribution {

  private NormalDistribution() {}

  // Abramowitz & Stegun 26.2.17 polynomial coefficients (|error| < 7.5e-8).
  private static final double A1 = 0.319381530;
  private static final double A2 = -0.356563782;
  private static final double A3 = 1.781477937;
  private static final double A4 = -1.821255978;
  private static final double A5 = 1.330274429;
  private static final double P = 0.2316419;

  private static final double SQRT_2PI = Math.sqrt(2.0 * Math.PI);

  /**
   * Bisection search bounds for {@link #inverseCdf}; ±10 is far enough into each tail (cdf ≈ 0/1 to
   * well beyond double precision) that no real input ever needs to search outside it.
   */
  private static final double SEARCH_BOUND = 10.0;

  /**
   * Fixed iteration count: interval width shrinks to {@code 20 / 2^100}, i.e. deterministically far
   * below any representable double — no early-exit epsilon to mistune.
   */
  private static final int BISECTION_ITERATIONS = 100;

  /**
   * Standard normal CDF, {@code Phi(x) = P(Z <= x)}. Abramowitz &amp; Stegun approximation
   * evaluated at {@code |x|}, reflected via the identity {@code Phi(-x) = 1 - Phi(x)}.
   */
  public static double cdf(double x) {
    double ax = Math.abs(x);
    double t = 1.0 / (1.0 + P * ax);
    double poly = t * (A1 + t * (A2 + t * (A3 + t * (A4 + t * A5))));
    double pdf = Math.exp(-ax * ax / 2.0) / SQRT_2PI;
    double cdfAtAbs = 1.0 - pdf * poly;
    return x >= 0 ? cdfAtAbs : 1.0 - cdfAtAbs;
  }

  /**
   * Inverse standard normal CDF (quantile function) via bisection on {@code [-10, 10]} against
   * {@link #cdf}. Deterministic — no closed-form approximation constants to mistype.
   *
   * @throws IllegalArgumentException if {@code p} is not in the open interval {@code (0, 1)}
   */
  public static double inverseCdf(double p) {
    if (p <= 0.0 || p >= 1.0) {
      throw new IllegalArgumentException("p must be in (0, 1), got: " + p);
    }
    double lo = -SEARCH_BOUND;
    double hi = SEARCH_BOUND;
    for (int i = 0; i < BISECTION_ITERATIONS; i++) {
      double mid = (lo + hi) / 2.0;
      if (cdf(mid) < p) {
        lo = mid;
      } else {
        hi = mid;
      }
    }
    return (lo + hi) / 2.0;
  }
}
