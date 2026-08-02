package app.viglide.core.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.domain.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Metrics}: Sharpe, max-drawdown and win-rate calculations against known
 * inputs.
 */
class MetricsTest {

  private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");

  // ── Sharpe ───────────────────────────────────────────────────────────────────────────────────

  @Test
  void sharpe_returnsZeroForEmptyOrSinglePointCurve() {
    assertThat(Metrics.annualisedSharpe(List.of())).isEqualTo(0.0);
    assertThat(Metrics.annualisedSharpe(List.of(new EquityPoint(T0, BigDecimal.valueOf(100)))))
        .isEqualTo(0.0);
  }

  @Test
  void sharpe_returnsZeroForFlatEquityCurve() {
    // Two days of equal closes ⇒ zero returns ⇒ undefined ratio (we return 0.0).
    List<EquityPoint> curve =
        List.of(
            new EquityPoint(T0, BigDecimal.valueOf(100)),
            new EquityPoint(T0.plusSeconds(86_400), BigDecimal.valueOf(100)),
            new EquityPoint(T0.plusSeconds(2 * 86_400), BigDecimal.valueOf(100)));
    assertThat(Metrics.annualisedSharpe(curve)).isEqualTo(0.0);
  }

  @Test
  void sharpe_positiveForSteadilyRisingDailyEquity() {
    // Daily closes: 100, 101, 102.01, 103.03 ⇒ +1% each day, zero variance.
    // Note: zero variance in returns ⇒ stdev = 0 ⇒ Sharpe defined as 0 by our policy.
    // To get a positive Sharpe we need some variance, so we'll alternate slightly.
    List<EquityPoint> curve =
        List.of(
            new EquityPoint(T0, BigDecimal.valueOf(100)),
            new EquityPoint(T0.plusSeconds(86_400), BigDecimal.valueOf(102)),
            new EquityPoint(T0.plusSeconds(2 * 86_400), BigDecimal.valueOf(103)),
            new EquityPoint(T0.plusSeconds(3 * 86_400), BigDecimal.valueOf(105)));
    double sharpe = Metrics.annualisedSharpe(curve);
    assertThat(sharpe).isPositive();
  }

  // ── Max drawdown ────────────────────────────────────────────────────────────────────────────

  @Test
  void maxDrawdown_isZeroForMonotonicallyRisingCurve() {
    List<EquityPoint> curve =
        List.of(
            new EquityPoint(T0, BigDecimal.valueOf(100)),
            new EquityPoint(T0.plusSeconds(1), BigDecimal.valueOf(110)),
            new EquityPoint(T0.plusSeconds(2), BigDecimal.valueOf(115)));
    assertThat(Metrics.maxDrawdown(curve)).isEqualByComparingTo("0");
  }

  @Test
  void maxDrawdown_capturesWorstPeakToTrough() {
    // peak=120 at index 2, trough=90 at index 4 ⇒ DD = (120-90)/120 = 0.25
    List<EquityPoint> curve =
        List.of(
            new EquityPoint(T0, BigDecimal.valueOf(100)),
            new EquityPoint(T0.plusSeconds(1), BigDecimal.valueOf(110)),
            new EquityPoint(T0.plusSeconds(2), BigDecimal.valueOf(120)),
            new EquityPoint(T0.plusSeconds(3), BigDecimal.valueOf(100)),
            new EquityPoint(T0.plusSeconds(4), BigDecimal.valueOf(90)),
            new EquityPoint(T0.plusSeconds(5), BigDecimal.valueOf(105)));
    BigDecimal dd = Metrics.maxDrawdown(curve);
    assertThat(dd.doubleValue()).isEqualTo(0.25, org.assertj.core.data.Offset.offset(1e-9));
  }

  // ── Win rate ─────────────────────────────────────────────────────────────────────────────────

  @Test
  void winRate_isZeroForNoTrades() {
    assertThat(Metrics.winRate(List.of())).isEqualTo(0.0);
  }

  @Test
  void winRate_fractionOfPositivePnlTrades() {
    List<Trade> trades =
        List.of(
            tradeWithPnl(10),
            tradeWithPnl(-5),
            tradeWithPnl(20),
            tradeWithPnl(-2),
            tradeWithPnl(7));
    assertThat(Metrics.winRate(trades)).isEqualTo(0.6); // 3/5
  }

  // ── SharpeStats (PLAN-008 Task C.1) ────────────────────────────────────────────────────────────

  @Test
  void sharpeStats_degenerateForFewerThanTwoDailySamples() {
    assertThat(Metrics.sharpeStats(List.of())).isEqualTo(new SharpeStats(0.0, 0.0, 0, 0.0, 0.0));
    SharpeStats singlePoint =
        Metrics.sharpeStats(List.of(new EquityPoint(T0, BigDecimal.valueOf(100))));
    assertThat(singlePoint.observations()).isEqualTo(0);
    assertThat(singlePoint.perPeriodSharpe()).isEqualTo(0.0);
  }

  @Test
  void sharpeStats_degenerateForFlatEquityCurve_butReportsRealObservationCount() {
    List<EquityPoint> curve =
        List.of(
            new EquityPoint(T0, BigDecimal.valueOf(100)),
            new EquityPoint(T0.plusSeconds(86_400), BigDecimal.valueOf(100)),
            new EquityPoint(T0.plusSeconds(2 * 86_400), BigDecimal.valueOf(100)));
    SharpeStats stats = Metrics.sharpeStats(curve);
    assertThat(stats.observations()).isEqualTo(2); // 3 daily closes ⇒ 2 returns
    assertThat(stats.perPeriodSharpe()).isEqualTo(0.0);
    assertThat(stats.skewness()).isEqualTo(0.0);
    assertThat(stats.kurtosis()).isEqualTo(0.0);
  }

  @Test
  void sharpeStats_bankruptPortfolio_isDegenerateWithNominalObservationCount() {
    List<EquityPoint> curve =
        List.of(
            new EquityPoint(T0, BigDecimal.valueOf(100)),
            new EquityPoint(T0.plusSeconds(86_400), BigDecimal.ZERO),
            new EquityPoint(T0.plusSeconds(2 * 86_400), BigDecimal.valueOf(50)));
    SharpeStats stats = Metrics.sharpeStats(curve);
    assertThat(stats.perPeriodSharpe()).isEqualTo(0.0);
    assertThat(stats.observations()).isEqualTo(2);
  }

  @Test
  void sharpeStats_annualisedSharpeMatchesLegacyAnnualisedSharpe() {
    List<EquityPoint> curve =
        List.of(
            new EquityPoint(T0, BigDecimal.valueOf(100)),
            new EquityPoint(T0.plusSeconds(86_400), BigDecimal.valueOf(102)),
            new EquityPoint(T0.plusSeconds(2 * 86_400), BigDecimal.valueOf(103)),
            new EquityPoint(T0.plusSeconds(3 * 86_400), BigDecimal.valueOf(105)));
    SharpeStats stats = Metrics.sharpeStats(curve);
    assertThat(stats.annualisedSharpe()).isEqualTo(Metrics.annualisedSharpe(curve));
    assertThat(stats.annualisedSharpe())
        .isCloseTo(
            stats.perPeriodSharpe() * Math.sqrt(365.0), org.assertj.core.data.Offset.offset(1e-12));
    assertThat(stats.observations()).isEqualTo(3); // 4 daily closes ⇒ 3 returns
  }

  @Test
  void sharpeStats_isIdenticalRegardlessOfIntraDaySamplingDensity() {
    // PLAN-009 Task B0: the annualisation formula resamples to one point per UTC day before doing
    // any Sharpe math, so it must not matter whether the source candles are 1h, 15m, or 1m — only
    // each day's LAST sample matters. Two curves with identical day-end values (100, 102, 103,
    // 105 — same as sharpeStats_annualisedSharpeMatchesLegacyAnnualisedSharpe above) but very
    // different intra-day sampling density must produce byte-identical SharpeStats.
    List<EquityPoint> daily =
        List.of(
            new EquityPoint(T0, BigDecimal.valueOf(100)),
            new EquityPoint(T0.plusSeconds(86_400), BigDecimal.valueOf(102)),
            new EquityPoint(T0.plusSeconds(2 * 86_400), BigDecimal.valueOf(103)),
            new EquityPoint(T0.plusSeconds(3 * 86_400), BigDecimal.valueOf(105)));

    List<EquityPoint> fineGrained =
        List.of(
            new EquityPoint(T0, BigDecimal.valueOf(100)), // day 0, only sample
            new EquityPoint(T0.plusSeconds(6 * 3_600), BigDecimal.valueOf(100)), // day 0, last
            new EquityPoint(T0.plusSeconds(25 * 3_600), BigDecimal.valueOf(101.5)), // day 1
            new EquityPoint(T0.plusSeconds(30 * 3_600), BigDecimal.valueOf(102)), // day 1, last
            new EquityPoint(T0.plusSeconds(50 * 3_600), BigDecimal.valueOf(102.5)), // day 2
            new EquityPoint(T0.plusSeconds(54 * 3_600), BigDecimal.valueOf(103)), // day 2, last
            new EquityPoint(T0.plusSeconds(76 * 3_600), BigDecimal.valueOf(104.5)), // day 3
            new EquityPoint(T0.plusSeconds(80 * 3_600), BigDecimal.valueOf(105))); // day 3, last

    SharpeStats coarseStats = Metrics.sharpeStats(daily);
    SharpeStats fineStats = Metrics.sharpeStats(fineGrained);

    assertThat(fineStats).isEqualTo(coarseStats);
    assertThat(fineStats.annualisedSharpe()).isEqualTo(coarseStats.annualisedSharpe());
  }

  // ── Probabilistic / Deflated Sharpe Ratio (PLAN-008 Task C.2/C.3) ──────────────────────────────

  @Test
  void psr_atZeroSharpeAgainstZeroBenchmark_isOneHalf() {
    // cdf(0) is only accurate to the A&S-approximation's ~1e-9, not bit-exact 0.5.
    SharpeStats stats = new SharpeStats(0.0, 0.0, 100, 0.3, 4.0);
    assertThat(Metrics.probabilisticSharpeRatio(stats, 0.0))
        .isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  void psr_fewerThanTwoObservations_isOneHalfRegardlessOfSharpe() {
    SharpeStats stats = new SharpeStats(2.5, 47.7, 1, 0.0, 3.0);
    assertThat(Metrics.probabilisticSharpeRatio(stats, 0.0)).isEqualTo(0.5);
  }

  @Test
  void psrDetailed_normalCase_reportsClampNotFired() {
    SharpeStats stats = new SharpeStats(0.15, 0.15 * Math.sqrt(365.0), 250, 0.1, 3.2);
    Metrics.PsrResult result = Metrics.probabilisticSharpeRatioDetailed(stats, 0.0);
    assertThat(result.radicandClamped()).isFalse();
    assertThat(result.probability()).isEqualTo(Metrics.probabilisticSharpeRatio(stats, 0.0));
  }

  @Test
  void psrDetailed_fewerThanTwoObservations_reportsClampNotFired() {
    // The <2-observations short circuit returns 0.5 before the radicand is ever computed -- not
    // a clamp, a different degenerate guard entirely.
    SharpeStats stats = new SharpeStats(2.5, 47.7, 1, 0.0, 3.0);
    assertThat(Metrics.probabilisticSharpeRatioDetailed(stats, 0.0).radicandClamped()).isFalse();
  }

  @Test
  void psrDetailed_extremeSkewKurtosis_reportsClampFired() {
    // sr=5, g3=10, g4=3 -> radicand = 1 - 10*5 + ((3-1)/4)*25 = 1 - 50 + 12.5 = -36.5 <= 0.
    SharpeStats stats = new SharpeStats(5.0, 5.0 * Math.sqrt(365.0), 100, 10.0, 3.0);
    Metrics.PsrResult result = Metrics.probabilisticSharpeRatioDetailed(stats, 0.0);
    assertThat(result.radicandClamped()).isTrue();
    // Still a finite probability (the whole point of the clamp), not NaN.
    assertThat(result.probability()).isFinite();
    assertThat(result.probability()).isEqualTo(Metrics.probabilisticSharpeRatio(stats, 0.0));
  }

  @Test
  void psr_strictlyIncreasesWithObservationCount() {
    // Fixed positive per-period Sharpe, normal-ish skew/kurtosis; only T varies.
    double psr30 = psrForObservations(30);
    double psr100 = psrForObservations(100);
    double psr365 = psrForObservations(365);
    assertThat(psr30).isLessThan(psr100);
    assertThat(psr100).isLessThan(psr365);
  }

  private static double psrForObservations(int t) {
    SharpeStats stats = new SharpeStats(0.1, 0.1 * Math.sqrt(365.0), t, 0.0, 3.0);
    return Metrics.probabilisticSharpeRatio(stats, 0.0);
  }

  @Test
  void dsr_isAtMostPsr_whenTrialsAndVarianceArePositive() {
    SharpeStats stats = new SharpeStats(0.15, 0.15 * Math.sqrt(365.0), 250, 0.1, 3.2);
    double psr = Metrics.probabilisticSharpeRatio(stats, 0.0);
    double dsr = Metrics.deflatedSharpeRatio(stats, 500, 0.02);
    assertThat(dsr).isLessThanOrEqualTo(psr);
  }

  @Test
  void dsr_equalsPsr_whenOnlyOneTrial() {
    SharpeStats stats = new SharpeStats(0.15, 0.15 * Math.sqrt(365.0), 250, 0.1, 3.2);
    double psr = Metrics.probabilisticSharpeRatio(stats, 0.0);
    assertThat(Metrics.deflatedSharpeRatio(stats, 1, 0.02)).isEqualTo(psr);
    // Non-positive variance is the other documented degeneracy — same collapse to PSR.
    assertThat(Metrics.deflatedSharpeRatio(stats, 500, 0.0)).isEqualTo(psr);
    assertThat(Metrics.deflatedSharpeRatio(stats, 500, -1.0)).isEqualTo(psr);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

  private static Trade tradeWithPnl(double pnl) {
    return new Trade(
        T0,
        T0.plusSeconds(60),
        Direction.BUY,
        BigDecimal.valueOf(100),
        BigDecimal.valueOf(100 + pnl),
        BigDecimal.ONE,
        BigDecimal.valueOf(pnl),
        ExitReason.SIGNAL);
  }
}
