package app.viglide.core.backtest;

import app.viglide.core.indicator.IndicatorMath;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Pure-function metrics answering "how much capital was actually at risk, and what did it earn"
 * (PLAN-013 Task A, review finding F2) — a deliberate sibling to {@link Metrics}, not a replacement
 * for it. {@link Metrics#sharpeStats} resamples to one point per UTC day and takes mean/stdev over
 * <em>every</em> day including ones with no position open; for a strategy that holds one position
 * for a few weeks a year, idle days dilute the variance and inflate the statistic (annualised
 * Sharpe of an otherwise-flat curve with {@code k} same-sign accrual days is {@code sqrt(k / (1 -
 * k/365))} — the size of the accrual cancels out entirely, only its day-count matters). These
 * metrics answer the economic question directly instead: not "does the curve look smooth", but
 * "what yield did the capital that was actually deployed earn, and how much of the capital-time
 * budget did it use".
 *
 * <p>Report these <strong>alongside</strong> {@link Metrics}'s statistics, never in place of them —
 * the historical notes stay comparable, and the gap between the two views is itself the finding.
 */
public final class EconomicMetrics {

  private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400L);
  private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365L);

  private EconomicMetrics() {}

  /**
   * Total capital-days deployed: Σ over trades of {@code notional × holdingDays}, where notional is
   * valued at entry price (never exit — the denominator must not be influenced by the trade's own
   * outcome) and holding time comes from the trade's own {@code entryTime}/{@code exitTime} (never
   * a bar count, which is not comparable across the 1m/1h/4h dataset mix). Zero trades ⇒ zero,
   * never a division error.
   */
  public static BigDecimal deployedCapitalDays(List<Trade> trades) {
    BigDecimal total = BigDecimal.ZERO;
    for (Trade t : trades) {
      BigDecimal notional = t.size().multiply(t.entryPrice(), IndicatorMath.MC);
      total = total.add(notional.multiply(holdingDays(t), IndicatorMath.MC), IndicatorMath.MC);
    }
    return total;
  }

  /**
   * Annualised yield actually earned on capital <em>while it was at risk</em>: {@code netPnL /
   * deployedCapitalDays × 365}. A strategy deploying 2% of equity for 90 days and earning 0.16% of
   * equity has a very different economic profile from the same 0.16% earned on 100% deployment for
   * a year — {@link Metrics#annualisedSharpe} cannot tell them apart, this can. Zero deployed
   * capital-days (no trades) ⇒ zero, never a division error.
   */
  public static BigDecimal returnOnDeployedCapital(BacktestResult result) {
    BigDecimal deployedDays = deployedCapitalDays(result.trades());
    if (deployedDays.signum() == 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal netPnl = netPnl(result.trades());
    return netPnl.divide(deployedDays, IndicatorMath.MC).multiply(DAYS_PER_YEAR, IndicatorMath.MC);
  }

  /**
   * What fraction of the capital-time budget ({@code startingEquity × totalDays}) was ever
   * deployed. Makes "honest idle" (PLAN-011 finding F9) a measured quantity instead of a narrative.
   * Zero-length backtest or zero starting equity ⇒ zero, never a division error.
   */
  public static BigDecimal deploymentRatio(BacktestResult result) {
    BigDecimal totalDays = totalDays(result.equityCurve());
    BigDecimal budget = result.startingEquity().multiply(totalDays, IndicatorMath.MC);
    if (budget.signum() == 0) {
      return BigDecimal.ZERO;
    }
    return deployedCapitalDays(result.trades()).divide(budget, IndicatorMath.MC);
  }

  /**
   * The same daily-resampled annualised Sharpe {@link Metrics#annualisedSharpe} computes, but over
   * only the days on which some trade was open — the direct antidote to the idle-day-dilution
   * artifact (PLAN-013 §0): idle days can no longer manufacture a low-variance denominator that
   * doesn't reflect any capital actually being at risk. Fewer than one qualifying return, or no
   * trades at all, ⇒ {@code 0.0}.
   */
  public static double deployedOnlySharpe(List<EquityPoint> equityCurve, List<Trade> trades) {
    if (trades.isEmpty()) {
      return 0.0;
    }
    List<Metrics.DatedClose> daily = Metrics.dailyClosesWithDay(equityCurve);
    if (daily.size() < 2) {
      return 0.0;
    }

    double[] allReturns = new double[daily.size() - 1];
    boolean[] deployed = new boolean[daily.size() - 1];
    for (int i = 1; i < daily.size(); i++) {
      BigDecimal prev = daily.get(i - 1).close();
      BigDecimal curr = daily.get(i).close();
      if (prev.signum() <= 0) {
        return 0.0; // bankrupt partway through — same degenerate treatment as Metrics.sharpeStats
      }
      allReturns[i - 1] = curr.subtract(prev).divide(prev, IndicatorMath.MC).doubleValue();
      deployed[i - 1] = isDeployed(daily.get(i).utcDay(), trades);
    }

    int deployedCount = 0;
    for (boolean d : deployed) {
      if (d) {
        deployedCount++;
      }
    }
    double[] deployedReturns = new double[deployedCount];
    int idx = 0;
    for (int i = 0; i < allReturns.length; i++) {
      if (deployed[i]) {
        deployedReturns[idx++] = allReturns[i];
      }
    }
    return Metrics.statsFromReturns(deployedReturns).annualisedSharpe();
  }

  /**
   * Ulcer Index (Martin &amp; McCann, 1987): {@code sqrt(mean(drawdown_pct^2))} over the
   * daily-resampled equity curve — a drawdown-aware alternative to Sharpe that penalises deep,
   * sustained drawdowns more than shallow, brief ones, and does not reward flatness the way an
   * idle-day-diluted Sharpe does. Expressed in percentage-point units (a value of 5.0 means a
   * typical drawdown depth around 5%).
   */
  public static double ulcerIndex(List<EquityPoint> equityCurve) {
    List<BigDecimal> daily = dailyCloses(equityCurve);
    if (daily.isEmpty()) {
      return 0.0;
    }
    BigDecimal peak = daily.get(0);
    double sumSquaredDrawdownPct = 0.0;
    for (BigDecimal close : daily) {
      if (close.compareTo(peak) > 0) {
        peak = close;
      }
      double drawdownPct =
          peak.signum() <= 0
              ? 0.0
              : peak.subtract(close).divide(peak, IndicatorMath.MC).doubleValue() * 100.0;
      sumSquaredDrawdownPct += drawdownPct * drawdownPct;
    }
    return Math.sqrt(sumSquaredDrawdownPct / daily.size());
  }

  /**
   * Calmar ratio: compound annualised return ÷ max drawdown — a drawdown-aware return measure that
   * does not reward flatness (unlike Sharpe on an idle-heavy curve, a strategy earns a high Calmar
   * only by combining real return with a shallow drawdown). Zero max drawdown or a zero/negative-
   * length backtest ⇒ {@code 0.0}, never a division error.
   */
  public static double calmar(BacktestResult result) {
    BigDecimal maxDd = result.maxDrawdown();
    if (maxDd.signum() == 0) {
      return 0.0;
    }
    double totalDaysD = totalDays(result.equityCurve()).doubleValue();
    if (totalDaysD <= 0) {
      return 0.0;
    }
    double base = 1.0 + result.totalReturn().doubleValue();
    double annualisedReturn = base <= 0 ? -1.0 : Math.pow(base, 365.0 / totalDaysD) - 1.0;
    return annualisedReturn / maxDd.doubleValue();
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────────

  private static BigDecimal holdingDays(Trade t) {
    long seconds = Duration.between(t.entryTime(), t.exitTime()).getSeconds();
    return BigDecimal.valueOf(seconds).divide(SECONDS_PER_DAY, IndicatorMath.MC);
  }

  private static BigDecimal netPnl(List<Trade> trades) {
    BigDecimal total = BigDecimal.ZERO;
    for (Trade t : trades) {
      total = total.add(t.pnl(), IndicatorMath.MC);
    }
    return total;
  }

  /** Elapsed days spanned by the equity curve (first sample to last), for the deployment budget. */
  private static BigDecimal totalDays(List<EquityPoint> equityCurve) {
    if (equityCurve.size() < 2) {
      return BigDecimal.ZERO;
    }
    Instant first = equityCurve.get(0).at();
    Instant last = equityCurve.get(equityCurve.size() - 1).at();
    long seconds = Duration.between(first, last).getSeconds();
    return BigDecimal.valueOf(seconds).divide(SECONDS_PER_DAY, IndicatorMath.MC);
  }

  /** Whether some trade's [entryTime, exitTime) interval overlaps the given UTC calendar day. */
  private static boolean isDeployed(long utcDay, List<Trade> trades) {
    Instant dayStart = LocalDate.ofEpochDay(utcDay).atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant dayEnd = dayStart.plus(1, ChronoUnit.DAYS);
    for (Trade t : trades) {
      if (t.entryTime().isBefore(dayEnd) && dayStart.isBefore(t.exitTime())) {
        return true;
      }
    }
    return false;
  }

  private static List<BigDecimal> dailyCloses(List<EquityPoint> equityCurve) {
    return Metrics.dailyClosesWithDay(equityCurve).stream().map(Metrics.DatedClose::close).toList();
  }
}
