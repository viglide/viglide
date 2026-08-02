package app.viglide.core.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.viglide.core.domain.Direction;
import app.viglide.core.indicator.IndicatorMath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EconomicMetrics} (PLAN-013 Task A, review finding F2): capital-deployment
 * metrics that answer "what did the capital actually at risk earn", as distinct from {@link
 * Metrics}'s curve-shape statistics.
 */
class EconomicMetricsTest {

  private static final Instant T0 = Instant.parse("2023-01-01T00:00:00Z");

  // ── deployedCapitalDays ──────────────────────────────────────────────────────────────────────

  @Test
  void deployedCapitalDays_zeroTrades_isZero() {
    assertThat(EconomicMetrics.deployedCapitalDays(List.of()))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void deployedCapitalDays_singleTrade_isNotionalTimesHoldingDays() {
    // size=2, entryPrice=100 -> notional=200; held exactly 10 days -> 2000 capital-days.
    Trade trade =
        trade(T0, T0.plusSeconds(10L * 86_400), BigDecimal.valueOf(2), BigDecimal.valueOf(100));
    assertThat(EconomicMetrics.deployedCapitalDays(List.of(trade)))
        .isEqualByComparingTo(BigDecimal.valueOf(2000));
  }

  @Test
  void deployedCapitalDays_sumsAcrossTrades() {
    Trade t1 = trade(T0, T0.plusSeconds(5L * 86_400), BigDecimal.ONE, BigDecimal.valueOf(100));
    Trade t2 = trade(T0, T0.plusSeconds(3L * 86_400), BigDecimal.ONE, BigDecimal.valueOf(100));
    // 500 + 300 = 800.
    assertThat(EconomicMetrics.deployedCapitalDays(List.of(t1, t2)))
        .isEqualByComparingTo(BigDecimal.valueOf(800));
  }

  // ── returnOnDeployedCapital ──────────────────────────────────────────────────────────────────

  @Test
  void returnOnDeployedCapital_zeroTrades_isZeroNotDivisionError() {
    BacktestResult result = resultWithTrades(List.of());
    assertThat(EconomicMetrics.returnOnDeployedCapital(result))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void returnOnDeployedCapital_yearLongFullyDeployedTrade_equalsItsOwnReturn() {
    // PLAN-013 Task A acceptance criterion, verbatim: a single trade held exactly 365 days at
    // exactly 100% of cash (notional == startingCash) earning exactly 10% of that notional must
    // yield returnOnDeployedCapital == 0.10 -- deployedCapitalDays == notional*365, so
    // netPnl/deployedCapitalDays*365 collapses to netPnl/notional, the trade's own return.
    BigDecimal startingCash = BigDecimal.valueOf(1000);
    Trade trade =
        new Trade(
            T0,
            T0.plusSeconds(365L * 86_400),
            Direction.BUY,
            BigDecimal.valueOf(100), // entryPrice
            BigDecimal.valueOf(110), // exitPrice (unused by the metric; entry-priced notional only)
            BigDecimal.TEN, // size -> notional = 10*100 = 1000 = 100% of cash
            BigDecimal.valueOf(100), // pnl -> 10% of the 1000 notional
            ExitReason.SIGNAL);
    BacktestResult result = resultWithTrades(startingCash, List.of(trade));
    assertThat(EconomicMetrics.returnOnDeployedCapital(result).doubleValue())
        .isCloseTo(0.10, within(1e-9));
  }

  // ── deploymentRatio ──────────────────────────────────────────────────────────────────────────

  @Test
  void deploymentRatio_zeroLengthCurve_isZeroNotDivisionError() {
    BacktestResult result = resultWithTrades(List.of());
    assertThat(EconomicMetrics.deploymentRatio(result)).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void deploymentRatio_halfCapitalHalfTime_isOneQuarter() {
    // startingCash=1000 over 100 days -> budget = 100,000 capital-days. One trade: size*entry=500
    // (50% of cash) held 50 days (50% of the window) -> 25,000 capital-days -> ratio = 0.25.
    BigDecimal startingCash = BigDecimal.valueOf(1000);
    List<EquityPoint> curve =
        List.of(
            new EquityPoint(T0, startingCash),
            new EquityPoint(T0.plusSeconds(100L * 86_400), startingCash));
    Trade trade = trade(T0, T0.plusSeconds(50L * 86_400), BigDecimal.TEN, BigDecimal.valueOf(50));
    BacktestResult result =
        new BacktestResult(
            startingCash,
            startingCash,
            BigDecimal.ZERO,
            0.0,
            BigDecimal.ZERO,
            0.0,
            1,
            List.of(trade),
            curve,
            Map.of());
    assertThat(EconomicMetrics.deploymentRatio(result).doubleValue()).isCloseTo(0.25, within(1e-9));
  }

  // ── deployedOnlySharpe ───────────────────────────────────────────────────────────────────────

  @Test
  void deployedOnlySharpe_zeroTrades_isZero() {
    List<EquityPoint> curve =
        List.of(
            new EquityPoint(T0, BigDecimal.valueOf(100)),
            new EquityPoint(T0.plusSeconds(86_400), BigDecimal.valueOf(101)));
    assertThat(EconomicMetrics.deployedOnlySharpe(curve, List.of())).isEqualTo(0.0);
  }

  @Test
  void deployedOnlySharpe_wholeCurveDeployed_matchesAllDaysSharpeExactly() {
    // No idle days to exclude -> the filtered series is the whole series -> identical to
    // Metrics.annualisedSharpe. This is the unambiguous correctness check: the filtering
    // mechanism itself, isolated from any question about which direction dilution pushes it.
    List<EquityPoint> curve =
        List.of(
            new EquityPoint(T0, BigDecimal.valueOf(100)),
            new EquityPoint(T0.plusSeconds(86_400), BigDecimal.valueOf(102)),
            new EquityPoint(T0.plusSeconds(2 * 86_400), BigDecimal.valueOf(101)),
            new EquityPoint(T0.plusSeconds(3 * 86_400), BigDecimal.valueOf(105)));
    // exitTime strictly after day 3's start (half-open [entry, exit) interval semantics in
    // isDeployed) -- exactly T0+3d would leave day 3 itself just outside the trade's own window.
    Trade trade =
        trade(T0, T0.plusSeconds(3 * 86_400 + 1), BigDecimal.ONE, BigDecimal.valueOf(100));
    double allDays = Metrics.annualisedSharpe(curve);
    double deployedOnly = EconomicMetrics.deployedOnlySharpe(curve, List.of(trade));
    assertThat(deployedOnly).isCloseTo(allDays, within(1e-9));
  }

  @Test
  void deployedOnlySharpe_excludesReturnsOnDaysNoTradeWasOpen() {
    // 5 daily closes -> 4 returns (days 1-4). Only day 2 (T0+2d) has a trade open. The filtered
    // Sharpe must equal a single-point degenerate result (one observation -> zero variance ->
    // degenerate 0.0, matching Metrics.sharpeStats' own convention for <2 observations' worth of
    // spread), proving specifically that days 1, 3 and 4 were excluded, not just "some" days.
    List<EquityPoint> curve =
        List.of(
            new EquityPoint(T0, BigDecimal.valueOf(100)),
            new EquityPoint(
                T0.plusSeconds(86_400), BigDecimal.valueOf(150)), // day 1: huge move, excluded
            new EquityPoint(
                T0.plusSeconds(2 * 86_400), BigDecimal.valueOf(151)), // day 2: trade open
            new EquityPoint(
                T0.plusSeconds(3 * 86_400), BigDecimal.valueOf(50)), // day 3: huge move, excluded
            new EquityPoint(
                T0.plusSeconds(4 * 86_400), BigDecimal.valueOf(300))); // day 4: huge move, excluded
    Trade trade =
        trade(
            T0.plusSeconds(2 * 86_400),
            T0.plusSeconds(2 * 86_400 + 3600),
            BigDecimal.ONE,
            BigDecimal.valueOf(151));
    double deployedOnly = EconomicMetrics.deployedOnlySharpe(curve, List.of(trade));
    // A single filtered return has stdev 0 over 1 sample -> degenerate, i.e. 0.0. If the huge
    // excluded moves had leaked in, this would be some large nonzero number instead.
    assertThat(deployedOnly).isEqualTo(0.0);
  }

  @Test
  void deployedOnlySharpe_onIdleHeavyCurve_isDramaticallyHigherThanAllDaysSharpe_notLower() {
    // PLAN-013 Task A's own acceptance criterion, as literally written, predicts the OPPOSITE of
    // what this test asserts: an "ETHUSDT-2023-shaped" fixture (2 trades, ~85 accrual days in a
    // 365-day year, tiny net return) was expected to show deployedOnlySharpe "dramatically lower"
    // than the all-days figure. That prediction does not hold, and this is not a fixture-
    // construction failure -- it is a provable property of the statistic as specified (same fixed
    // sqrt(365) annualisation, filtered to deployed days), confirmed three independent ways before
    // this test was written this way: (1) hand-derivation, (2) a Python numerical check across
    // several noise regimes, (3) this exact fixture against the real implementation. For a "T
    // total days, k deployed days, (T-k) exactly-flat idle days" curve:
    //
    //   deployedOnlySharpe / allDaysSharpe >= sqrt(T/k) * sqrt(1 + (activeMean/activeStdev)^2)
    //                                      >= sqrt(T/k)
    //
    // which for k~85, T=365 floors at ~2.07x -- always in the higher direction, for every
    // non-degenerate case. The ONLY way to make it lower is exact zero variance among the
    // deployed-day returns (a literal division-by-zero-avoidance degenerate case, itself reported
    // as 0.0) -- and that is fragile in practice: this fixture originally tried to construct
    // exactly that (identical compounding multiplier every deployed day) and still measured
    // deployedOnlySharpe = 122.3 against an all-days figure of ~4.6, because BigDecimal DECIMAL64
    // rounding across dozens of compounding steps is not perfectly exact, and the tiniest residual
    // variance in a near-zero-variance series explodes the ratio rather than producing a clean
    // zero. Restricting a Sharpe computation to fewer, more-concentrated "good" days without
    // shrinking the annualisation constant to match mechanically inflates it; it does not act as a
    // stricter filter. Reported for Task C (ADR-0016, K1' condition 3): do not set a
    // deployedOnlySharpe threshold expecting it to behave as a conservative cousin of Sharpe -- for
    // a genuinely idle-heavy carry strategy it is typically laxer, not stricter.
    List<EquityPoint> curve = new ArrayList<>();
    BigDecimal equity = BigDecimal.valueOf(10_000);
    curve.add(new EquityPoint(T0, equity));
    int day = 0;
    for (; day < 50; day++) { // idle
      curve.add(new EquityPoint(dayInstant(day + 1), equity));
    }
    Instant trade1Entry = dayInstant(day);
    BigDecimal dailyAccrual = new BigDecimal("1.00004");
    for (; day < 93; day++) { // trade 1 open: 43 near-identical-return days
      equity = equity.multiply(dailyAccrual, IndicatorMath.MC);
      curve.add(new EquityPoint(dayInstant(day + 1), equity));
    }
    Instant trade1Exit = dayInstant(day);
    for (; day < 200; day++) { // idle
      curve.add(new EquityPoint(dayInstant(day + 1), equity));
    }
    Instant trade2Entry = dayInstant(day);
    for (; day < 242; day++) { // trade 2 open: 42 near-identical-return days
      equity = equity.multiply(dailyAccrual, IndicatorMath.MC);
      curve.add(new EquityPoint(dayInstant(day + 1), equity));
    }
    Instant trade2Exit = dayInstant(day);
    for (; day < 364; day++) { // idle for the rest of the year
      curve.add(new EquityPoint(dayInstant(day + 1), equity));
    }

    List<Trade> trades =
        List.of(
            trade(trade1Entry, trade1Exit, BigDecimal.ONE, BigDecimal.valueOf(10_000)),
            trade(trade2Entry, trade2Exit, BigDecimal.ONE, BigDecimal.valueOf(10_000)));

    double allDaysSharpe = Metrics.annualisedSharpe(curve);
    double deployedOnlySharpe = EconomicMetrics.deployedOnlySharpe(curve, trades);

    assertThat(allDaysSharpe).isGreaterThan(3.0); // confirms the idle-dilution artifact is present
    assertThat(deployedOnlySharpe).isGreaterThan(allDaysSharpe); // the actual, verified direction
  }

  // ── ulcerIndex ───────────────────────────────────────────────────────────────────────────────

  @Test
  void ulcerIndex_flatCurve_isZero() {
    List<EquityPoint> curve =
        List.of(
            new EquityPoint(T0, BigDecimal.valueOf(100)),
            new EquityPoint(T0.plusSeconds(86_400), BigDecimal.valueOf(100)),
            new EquityPoint(T0.plusSeconds(2 * 86_400), BigDecimal.valueOf(100)));
    assertThat(EconomicMetrics.ulcerIndex(curve)).isEqualTo(0.0);
  }

  @Test
  void ulcerIndex_isPositiveUnderDrawdown() {
    List<EquityPoint> curve =
        List.of(
            new EquityPoint(T0, BigDecimal.valueOf(100)),
            new EquityPoint(T0.plusSeconds(86_400), BigDecimal.valueOf(80)),
            new EquityPoint(T0.plusSeconds(2 * 86_400), BigDecimal.valueOf(90)));
    assertThat(EconomicMetrics.ulcerIndex(curve)).isGreaterThan(0.0);
  }

  // ── calmar ───────────────────────────────────────────────────────────────────────────────────

  @Test
  void calmar_zeroDrawdown_isZeroNotDivisionError() {
    BacktestResult result =
        new BacktestResult(
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(110),
            BigDecimal.valueOf(0.10),
            0.0,
            BigDecimal.ZERO,
            0.0,
            0,
            List.of(),
            List.of(
                new EquityPoint(T0, BigDecimal.valueOf(100)),
                new EquityPoint(T0.plusSeconds(86_400), BigDecimal.valueOf(110))),
            Map.of());
    assertThat(EconomicMetrics.calmar(result)).isEqualTo(0.0);
  }

  @Test
  void calmar_positiveReturnWithDrawdown_isPositive() {
    BacktestResult result =
        new BacktestResult(
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(110),
            BigDecimal.valueOf(0.10),
            0.0,
            BigDecimal.valueOf(0.05),
            0.0,
            0,
            List.of(),
            List.of(
                new EquityPoint(T0, BigDecimal.valueOf(100)),
                new EquityPoint(T0.plusSeconds(365L * 86_400), BigDecimal.valueOf(110))),
            Map.of());
    assertThat(EconomicMetrics.calmar(result)).isGreaterThan(0.0);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────────

  private static Instant dayInstant(int day) {
    return T0.plusSeconds(day * 86_400L);
  }

  private static Trade trade(Instant entry, Instant exit, BigDecimal size, BigDecimal entryPrice) {
    return new Trade(
        entry,
        exit,
        Direction.BUY,
        entryPrice,
        entryPrice,
        size,
        BigDecimal.ZERO,
        ExitReason.SIGNAL);
  }

  private static BacktestResult resultWithTrades(List<Trade> trades) {
    return resultWithTrades(BigDecimal.valueOf(1000), trades);
  }

  private static BacktestResult resultWithTrades(BigDecimal startingCash, List<Trade> trades) {
    List<EquityPoint> curve =
        List.of(
            new EquityPoint(T0, startingCash),
            new EquityPoint(T0.plusSeconds(86_400), startingCash));
    return new BacktestResult(
        startingCash,
        startingCash,
        BigDecimal.ZERO,
        0.0,
        BigDecimal.ZERO,
        0.0,
        trades.size(),
        trades,
        curve,
        Map.of());
  }
}
