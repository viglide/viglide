package app.viglide.core.backtest;

import app.viglide.core.indicator.IndicatorMath;
import app.viglide.core.stat.NormalDistribution;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-function metric calculators over an equity curve and trade list.
 *
 * <ul>
 *   <li>{@link #annualisedSharpe} resamples the equity curve to daily closes and computes {@code
 *       mean(daily_returns) / stdev(daily_returns) * sqrt(365)} ({@link
 *       SharpeStats#ANNUALISATION_FACTOR}) — the source candles' bar interval is irrelevant here:
 *       resampling to one point per UTC day (see {@link #dailyCloses}) makes the "period" always a
 *       day, never a bar, so this is safe to feed 1m/5m/15m/4h/1d series unchanged (PLAN-009 Task
 *       B0 audit, verified 2026-07-14 — {@code barsPerDay} below is <em>not</em> part of this
 *       formula despite an earlier version of this comment implying otherwise).
 *   <li>{@link #sharpeStats} computes the same daily-resampled returns once and reuses them for
 *       skewness/kurtosis, feeding {@link #probabilisticSharpeRatio} and {@link
 *       #deflatedSharpeRatio} (PLAN-008 Task C).
 *   <li>{@link #maxDrawdown} walks the running peak and reports the worst peak-to-trough drop as a
 *       non-negative fraction (1.0 = total wipeout).
 *   <li>{@link #winRate} = wins / total trades; 0.0 when there are no trades.
 * </ul>
 *
 * <p>Crypto trades 24/7 so the annualisation factor uses 365 days, not the equity-market 252.
 *
 * <p><strong>The T≈days convention (PLAN-010 D10-4, finding F5).</strong> Every statistic in this
 * class is computed on the equity curve resampled to one point per UTC day — so the sample size
 * feeding {@link #probabilisticSharpeRatio} and {@link #deflatedSharpeRatio} ({@link
 * SharpeStats#observations()}) is the number of <em>days</em> the backtest ran, not the number of
 * <em>trades</em> it made. A strategy that traded 3 times over a full year still gets a PSR built
 * on T≈365 daily observations — idle days between trades count as evidence of "no adverse move
 * happened", which is a much weaker claim than the PSR's headline framing suggests. A high
 * PSR/Sharpe on a low trade count is not wrong, but it is not what most readers assume it means;
 * always read it next to the actual trade count ({@link BacktestResult#tradeCount()}), and treat
 * anything below {@link #MIN_TRADES_FOR_STATS} as noise-level evidence regardless of how the
 * statistics look. See {@code docs/runbook.md} §6 for the reader-facing version of this caveat.
 */
public final class Metrics {

  /** Euler–Mascheroni constant, used by {@link #deflatedSharpeRatio}'s expected-max-Sharpe term. */
  private static final double EULER_MASCHERONI = 0.5772156649015329;

  /**
   * PLAN-010 D10-4: below this many trades, a reported Sharpe/PSR/DSR is disclosed as noise-level
   * rather than presented at face value — see this class's T≈days caveat above. Reporting layers
   * (CLI summaries, {@code scripts/research_matrix.py}'s {@code MIN_TRADES_FOR_STATS}, which
   * mirrors this constant for the Python side) flag cells below this threshold; the value itself is
   * a disclosure convention, not a statistical derivation — 10 is simply "few enough that a reader
   * should stop and look at the trade log before trusting the ratio".
   */
  public static final int MIN_TRADES_FOR_STATS = 10;

  private Metrics() {}

  /**
   * Annualised Sharpe ratio over the equity curve resampled to one point per UTC day. Returns
   * {@code 0.0} when fewer than two daily samples or when the daily-returns stddev is zero.
   *
   * <p>Delegates to {@link #sharpeStats}; behaviour is unchanged from before that refactor.
   */
  public static double annualisedSharpe(List<EquityPoint> equityCurve) {
    return sharpeStats(equityCurve).annualisedSharpe();
  }

  /**
   * Distribution statistics (Sharpe, skewness, kurtosis) of the equity curve resampled to one point
   * per UTC day (PLAN-008 Task C.1). Degenerate inputs — fewer than 2 return observations, a
   * bankrupt portfolio (non-positive equity partway through), or zero return variance — yield
   * {@link SharpeStats#degenerate(int)} with {@link SharpeStats#observations()} still set to the
   * true count.
   */
  public static SharpeStats sharpeStats(List<EquityPoint> equityCurve) {
    List<DatedClose> daily = dailyClosesWithDay(equityCurve);
    if (daily.size() < 2) return SharpeStats.degenerate(0);

    double[] returns = new double[daily.size() - 1];
    for (int i = 1; i < daily.size(); i++) {
      BigDecimal prev = daily.get(i - 1).close();
      BigDecimal curr = daily.get(i).close();
      if (prev.signum() <= 0) {
        // Bankrupt portfolio partway through — the return series is compromised from here on;
        // report the nominal observation count but treat the statistics as degenerate.
        return SharpeStats.degenerate(daily.size() - 1);
      }
      returns[i - 1] = curr.subtract(prev).divide(prev, IndicatorMath.MC).doubleValue();
    }
    return statsFromReturns(returns);
  }

  /**
   * Shared by {@link #sharpeStats} and {@link EconomicMetrics#deployedOnlySharpe} (PLAN-013 Task A)
   * — the latter feeds the identical formula a filtered subsequence of daily returns, so the
   * mean/stdev/skew/kurtosis math must live in exactly one place.
   */
  static SharpeStats statsFromReturns(double[] returns) {
    int t = returns.length;
    if (t == 0) return SharpeStats.degenerate(0);
    double mean = mean(returns);
    double sd = sampleStdev(returns, mean);
    if (sd == 0.0) return SharpeStats.degenerate(t);

    double perPeriodSharpe = mean / sd;
    double annualisedSharpe = perPeriodSharpe * SharpeStats.ANNUALISATION_FACTOR;

    // Central moments with 1/n ("population") normalisation — deliberately different from the
    // 1/(n-1) sample stdev above; this mixed convention is what the PSR derivation (Bailey &
    // López de Prado 2012) assumes.
    double m2 = 0.0;
    double m3 = 0.0;
    double m4 = 0.0;
    for (double x : returns) {
      double d = x - mean;
      double d2 = d * d;
      m2 += d2;
      m3 += d2 * d;
      m4 += d2 * d2;
    }
    m2 /= t;
    m3 /= t;
    m4 /= t;
    double skewness = m3 / Math.pow(m2, 1.5);
    double kurtosis = m4 / (m2 * m2);

    return new SharpeStats(perPeriodSharpe, annualisedSharpe, t, skewness, kurtosis);
  }

  /**
   * Probabilistic Sharpe Ratio (Bailey &amp; López de Prado, 2012): the probability that the true
   * per-period Sharpe ratio exceeds {@code benchmarkPerPeriodSharpe}, given the observed {@link
   * SharpeStats} (PLAN-008 Task C.2).
   *
   * <p>Guards: fewer than 2 observations → {@code 0.5} (no evidence either way, not "no skill"). A
   * radicand that goes non-positive (extreme skew/kurtosis combinations) is clamped to {@code 1e-8}
   * — a documented convention so the ratio stays a finite probability instead of {@code NaN}, not a
   * claim of statistical validity in that regime. Delegates to {@link
   * #probabilisticSharpeRatioDetailed} — use that overload instead of this one when the caller
   * needs to know whether the clamp fired (PLAN-013 Task B, review finding F14).
   */
  public static double probabilisticSharpeRatio(
      SharpeStats stats, double benchmarkPerPeriodSharpe) {
    return probabilisticSharpeRatioDetailed(stats, benchmarkPerPeriodSharpe).probability();
  }

  /** {@link #probabilisticSharpeRatio}, plus whether the radicand clamp fired (PLAN-013 Task B). */
  public record PsrResult(double probability, boolean radicandClamped) {}

  /**
   * Same computation as {@link #probabilisticSharpeRatio}, additionally reporting whether the
   * radicand clamp fired for this cell — every reporting layer must flag a clamped cell with the
   * same {@code †}-style discipline PLAN-010 introduced for small-N (review finding F14: the clamp
   * was silent, so a reader had no way to tell a normally-computed PSR from one produced by the
   * {@code 1e-8} fallback).
   */
  public static PsrResult probabilisticSharpeRatioDetailed(
      SharpeStats stats, double benchmarkPerPeriodSharpe) {
    int t = stats.observations();
    if (t < 2) return new PsrResult(0.5, false);
    double sr = stats.perPeriodSharpe();
    double g3 = stats.skewness();
    double g4 = stats.kurtosis();
    double numerator = (sr - benchmarkPerPeriodSharpe) * Math.sqrt(t - 1);
    double radicand = 1 - g3 * sr + ((g4 - 1) / 4.0) * sr * sr;
    boolean clamped = radicand <= 0;
    if (clamped) radicand = 1e-8;
    double probability = NormalDistribution.cdf(numerator / Math.sqrt(radicand));
    return new PsrResult(probability, clamped);
  }

  /**
   * Deflated Sharpe Ratio (Bailey &amp; López de Prado, 2014): {@link #probabilisticSharpeRatio}
   * evaluated against a benchmark that accounts for having selected the best of {@code trials}
   * calibration candidates — a multiple-testing / selection-bias correction (PLAN-008 Task C.3).
   * {@code trialSharpeVariancePerPeriod} is the variance, across all evaluated candidates, of their
   * per-period Sharpe ratios.
   *
   * <p>Treats every calibration candidate as a trial from a common zero-skill process — an
   * approximation (candidates are not identically distributed); documented in ADR-0009.
   *
   * <p>{@code trials <= 1} or {@code trialSharpeVariancePerPeriod <= 0} degenerates the benchmark
   * to zero, so DSR collapses to plain {@code PSR(0)} — there is no selection to correct for.
   */
  public static double deflatedSharpeRatio(
      SharpeStats stats, int trials, double trialSharpeVariancePerPeriod) {
    double sr0 = 0.0;
    if (trials > 1 && trialSharpeVariancePerPeriod > 0) {
      double n = trials;
      sr0 =
          Math.sqrt(trialSharpeVariancePerPeriod)
              * ((1 - EULER_MASCHERONI) * NormalDistribution.inverseCdf(1 - 1.0 / n)
                  + EULER_MASCHERONI * NormalDistribution.inverseCdf(1 - 1.0 / (n * Math.E)));
    }
    return probabilisticSharpeRatio(stats, sr0);
  }

  /** Max drawdown as a non-negative fraction of the running peak. */
  public static BigDecimal maxDrawdown(List<EquityPoint> equityCurve) {
    if (equityCurve.isEmpty()) return BigDecimal.ZERO;
    BigDecimal peak = equityCurve.get(0).equity();
    BigDecimal maxDd = BigDecimal.ZERO;
    for (EquityPoint p : equityCurve) {
      if (p.equity().compareTo(peak) > 0) peak = p.equity();
      if (peak.signum() <= 0) continue;
      BigDecimal dd = peak.subtract(p.equity()).divide(peak, IndicatorMath.MC);
      if (dd.compareTo(maxDd) > 0) maxDd = dd;
    }
    return maxDd;
  }

  /** Fraction of trades with positive PnL. Zero trades ⇒ 0.0 (avoids NaN). */
  public static double winRate(List<Trade> trades) {
    if (trades.isEmpty()) return 0.0;
    long wins = trades.stream().filter(t -> t.pnl().signum() > 0).count();
    return (double) wins / trades.size();
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────────

  /** One calendar day's closing equity, tagged with its UTC epoch-day for deployment lookups. */
  record DatedClose(long utcDay, BigDecimal close) {}

  /**
   * Resamples the equity curve to one closing value per UTC day. Uses the LAST equity sample that
   * falls on each calendar day.
   */
  private static List<BigDecimal> dailyCloses(List<EquityPoint> equityCurve) {
    List<BigDecimal> out = new ArrayList<>();
    for (DatedClose dc : dailyClosesWithDay(equityCurve)) {
      out.add(dc.close());
    }
    return out;
  }

  /**
   * Same resampling as {@link #dailyCloses}, but keeps each close's calendar day — needed by {@link
   * EconomicMetrics#deployedOnlySharpe} (PLAN-013 Task A) to test which daily returns fall on a day
   * some trade was open.
   */
  static List<DatedClose> dailyClosesWithDay(List<EquityPoint> equityCurve) {
    List<DatedClose> out = new ArrayList<>();
    if (equityCurve.isEmpty()) return out;
    long currentDay = utcDay(equityCurve.get(0).at());
    BigDecimal currentClose = equityCurve.get(0).equity();
    for (int i = 1; i < equityCurve.size(); i++) {
      EquityPoint p = equityCurve.get(i);
      long day = utcDay(p.at());
      if (day != currentDay) {
        out.add(new DatedClose(currentDay, currentClose));
        currentDay = day;
      }
      currentClose = p.equity();
    }
    out.add(new DatedClose(currentDay, currentClose)); // flush final day
    return out;
  }

  static long utcDay(java.time.Instant t) {
    return t.atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay();
  }

  private static double mean(double[] xs) {
    double sum = 0.0;
    for (double x : xs) sum += x;
    return sum / xs.length;
  }

  /** Sample standard deviation (n-1 denominator). */
  private static double sampleStdev(double[] xs, double mean) {
    if (xs.length < 2) return 0.0;
    double sumSq = 0.0;
    for (double x : xs) {
      double d = x - mean;
      sumSq += d * d;
    }
    return Math.sqrt(sumSq / (xs.length - 1));
  }

  /**
   * Approximate bars per day for a given candle interval. Used by callers to size warmup; not part
   * of Sharpe computation (Sharpe resamples explicitly to daily).
   */
  static long barsPerDay(java.time.Duration barDuration) {
    long seconds = barDuration.get(ChronoUnit.SECONDS);
    if (seconds <= 0) return 1;
    return Math.max(1L, 86_400L / seconds);
  }
}
