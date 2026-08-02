package app.viglide.core.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.Factor;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.indicator.IndicatorMath;
import app.viglide.core.risk.ExecutionDecision;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VirtualExchange}: fee/slippage math, signal flow timing, stop-loss and
 * take-profit triggers.
 */
class VirtualExchangeTest {

  private static final String SYM = "BTCUSDT";

  // ── Signal flow: BUY then SELL round trip ────────────────────────────────────────────────────

  @Test
  void buySignalFillsAtNextBarOpen_andSellSignalClosesAtFollowingOpen() {
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 1, new BigDecimal("1.0"), null, null, 8760);
    VirtualExchange ex = new VirtualExchange(SYM, cfg, CandleInterval.ONE_HOUR);

    Candle c1 = bar(0, 100, 100, 100, 100);
    Candle c2 = bar(1, 110, 110, 110, 110);
    Candle c3 = bar(2, 120, 120, 120, 120);
    Candle c4 = bar(3, 105, 105, 105, 105);

    ex.onCandle(c1);
    ex.onSignal(signal(Direction.BUY));
    ex.onCandle(c2); // BUY fills at 110
    ex.onCandle(c3);
    ex.onSignal(signal(Direction.SELL));
    ex.onCandle(c4); // SELL fills at 105

    List<Trade> trades = ex.trades();
    assertThat(trades).hasSize(1);
    Trade t = trades.get(0);
    assertThat(t.entryPrice()).isEqualByComparingTo("110");
    assertThat(t.exitPrice()).isEqualByComparingTo("105");
    assertThat(t.exitReason()).isEqualTo(ExitReason.SIGNAL);
    // With zero fees, PnL = size * (105 - 110) = (10000/110) * -5 ≈ -454.55
    assertThat(t.pnl().signum()).isNegative();
  }

  @Test
  void feesAndSlippageAdverselyMoveFillPrice() {
    // 5 bps taker + 5 bps slippage = 0.1% adverse on each side.
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.binanceDefault(),
            1,
            new BigDecimal("1.0"),
            null,
            null,
            8760);
    VirtualExchange ex = new VirtualExchange(SYM, cfg, CandleInterval.ONE_HOUR);

    ex.onCandle(bar(0, 100, 100, 100, 100));
    ex.onSignal(signal(Direction.BUY));
    ex.onCandle(bar(1, 100, 100, 100, 100)); // open = 100, fills at 100 * (1 + 0.001) = 100.1
    ex.onSignal(signal(Direction.SELL));
    ex.onCandle(bar(2, 100, 100, 100, 100)); // exits at 100 * (1 - 0.001) = 99.9

    Trade t = ex.trades().get(0);
    assertThat(t.entryPrice()).isEqualByComparingTo("100.1");
    assertThat(t.exitPrice()).isEqualByComparingTo("99.9");
  }

  @Test
  void totalFeesPaid_tracksTheAdverseCostOfEveryFill_separatelyFromNetPnl() {
    // PLAN-009 Task C: fee share of gross P&L needs a real fee figure per cell, not just the
    // adverse cost implicitly baked into entry/exit prices.
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.binanceDefault(), // 5bps taker + 5bps slippage = 0.001 total adverse factor
            1,
            new BigDecimal("1.0"),
            null,
            null,
            8760);
    VirtualExchange ex = new VirtualExchange(SYM, cfg, CandleInterval.ONE_HOUR);

    ex.onCandle(bar(0, 100, 100, 100, 100));
    ex.onSignal(signal(Direction.BUY));
    ex.onCandle(bar(1, 100, 100, 100, 100)); // entry fill = 100 * 1.001 = 100.1
    ex.onSignal(signal(Direction.SELL));
    ex.onCandle(bar(2, 100, 100, 100, 100)); // exit fill = 100 * 0.999 = 99.9

    // size = notional / entryFillPrice = 10000 / 100.1; entry fee = size * (100.1 - 100);
    // exit fee = size * (100 - 99.9). Both legs' adverse cost is the same size * 0.1.
    BigDecimal size = new BigDecimal("10000").divide(new BigDecimal("100.1"), IndicatorMath.MC);
    BigDecimal expectedFees =
        size.multiply(new BigDecimal("0.1"), IndicatorMath.MC)
            .multiply(BigDecimal.TWO, IndicatorMath.MC);

    assertThat(ex.totalFeesPaid())
        .isCloseTo(expectedFees, org.assertj.core.data.Percentage.withPercentage(0.01));
    assertThat(ex.totalFeesPaid().signum()).isPositive();
  }

  @Test
  void totalFeesPaid_isZeroWithZeroFees() {
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 1, new BigDecimal("1.0"), null, null, 8760);
    VirtualExchange ex = new VirtualExchange(SYM, cfg, CandleInterval.ONE_HOUR);

    ex.onCandle(bar(0, 100, 100, 100, 100));
    ex.onSignal(signal(Direction.BUY));
    ex.onCandle(bar(1, 100, 100, 100, 100));
    ex.onSignal(signal(Direction.SELL));
    ex.onCandle(bar(2, 105, 105, 105, 105));

    assertThat(ex.totalFeesPaid()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  // ── Stop-loss ────────────────────────────────────────────────────────────────────────────────

  @Test
  void stopLossFiresWhenNextBarLowCrossesStop() {
    // 2% stop. Entry at 100 → stop at 98.
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.zero(),
            1,
            new BigDecimal("1.0"),
            new BigDecimal("0.02"),
            null,
            8760);
    VirtualExchange ex = new VirtualExchange(SYM, cfg, CandleInterval.ONE_HOUR);

    ex.onCandle(bar(0, 100, 100, 100, 100));
    ex.onSignal(signal(Direction.BUY));
    ex.onCandle(bar(1, 100, 100, 100, 100)); // entry at 100
    ex.onCandle(bar(2, 100, 100, 97, 99)); // low 97 < stop 98 → SL hits at 98

    List<Trade> trades = ex.trades();
    assertThat(trades).hasSize(1);
    assertThat(trades.get(0).exitReason()).isEqualTo(ExitReason.STOP_LOSS);
    assertThat(trades.get(0).exitPrice()).isEqualByComparingTo("98");
  }

  // ── Take-profit ──────────────────────────────────────────────────────────────────────────────

  @Test
  void takeProfitFiresWhenNextBarHighCrossesTarget() {
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.zero(),
            1,
            new BigDecimal("1.0"),
            null,
            new BigDecimal("0.05"),
            8760);
    VirtualExchange ex = new VirtualExchange(SYM, cfg, CandleInterval.ONE_HOUR);

    ex.onCandle(bar(0, 100, 100, 100, 100));
    ex.onSignal(signal(Direction.BUY));
    ex.onCandle(bar(1, 100, 100, 100, 100)); // entry at 100 → TP at 105
    ex.onCandle(bar(2, 100, 110, 100, 108)); // high 110 ≥ TP 105 → TP hits at 105

    Trade t = ex.trades().get(0);
    assertThat(t.exitReason()).isEqualTo(ExitReason.TAKE_PROFIT);
    assertThat(t.exitPrice()).isEqualByComparingTo("105");
  }

  @Test
  void stopLossWinsTie_whenBothCouldFireInSameBar() {
    // SL at 98 and TP at 105; a bar that touches both (low 97, high 110) ⇒ assume SL.
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.zero(),
            1,
            new BigDecimal("1.0"),
            new BigDecimal("0.02"),
            new BigDecimal("0.05"),
            8760);
    VirtualExchange ex = new VirtualExchange(SYM, cfg, CandleInterval.ONE_HOUR);

    ex.onCandle(bar(0, 100, 100, 100, 100));
    ex.onSignal(signal(Direction.BUY));
    ex.onCandle(bar(1, 100, 100, 100, 100));
    ex.onCandle(bar(2, 100, 110, 97, 100)); // ambiguous bar

    assertThat(ex.trades().get(0).exitReason()).isEqualTo(ExitReason.STOP_LOSS);
  }

  // ── PLAN-009 Task B2: sub-bar SL/TP resolution ──────────────────────────────────────────────

  @Test
  void subBars_resolveAmbiguousBarByActualIntraBarOrder_tpFiresFirst() {
    // Same ambiguous decision bar as stopLossWinsTie_whenBothCouldFireInSameBar (SL=98, TP=105,
    // bar low=97 high=110, coarse model assumes SL) — but now with a 1m-style sub-bar path
    // showing TP (110) was actually touched before SL (97) inside that hour.
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.zero(),
            1,
            new BigDecimal("1.0"),
            new BigDecimal("0.02"),
            new BigDecimal("0.05"),
            8760);
    VirtualExchange ex = new VirtualExchange(SYM, cfg, CandleInterval.ONE_HOUR);

    ex.onCandle(bar(0, 100, 100, 100, 100));
    ex.onSignal(signal(Direction.BUY));
    ex.onCandle(bar(1, 100, 100, 100, 100)); // entry at 100 → SL 98, TP 105
    Candle decisionBar = bar(2, 100, 110, 97, 100);
    List<Candle> subBars =
        List.of(
            subBar(2, 0, 100, 103, 99, 102), // neither triggers yet
            subBar(2, 20, 102, 110, 101, 108), // TP (105) touched here first
            subBar(2, 40, 108, 108, 97, 100)); // SL (98) would touch here, but TP already exited

    ex.onCandle(decisionBar, subBars);

    List<Trade> trades = ex.trades();
    assertThat(trades).hasSize(1);
    assertThat(trades.get(0).exitReason()).isEqualTo(ExitReason.TAKE_PROFIT);
    assertThat(trades.get(0).exitPrice()).isEqualByComparingTo("105");
  }

  @Test
  void subBars_emptyList_preservesOriginalWorstCaseBehaviour() {
    // The two-arg onCandle(candle) is exactly onCandle(candle, List.of()) — same ambiguous bar,
    // no sub-bars supplied, still assumes SL (regression guard for the new overload's default).
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.zero(),
            1,
            new BigDecimal("1.0"),
            new BigDecimal("0.02"),
            new BigDecimal("0.05"),
            8760);
    VirtualExchange ex = new VirtualExchange(SYM, cfg, CandleInterval.ONE_HOUR);

    ex.onCandle(bar(0, 100, 100, 100, 100));
    ex.onSignal(signal(Direction.BUY));
    ex.onCandle(bar(1, 100, 100, 100, 100));
    ex.onCandle(bar(2, 100, 110, 97, 100), List.of());

    assertThat(ex.trades().get(0).exitReason()).isEqualTo(ExitReason.STOP_LOSS);
  }

  @Test
  void subBars_outsideParentRange_failsLoudly() {
    // Data-integrity guard: a sub-bar's high (111) exceeds the parent's high (110) — cannot have
    // aggregated from this sub-bar series, so trust nothing and fail loudly (CLAUDE.md §5).
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.zero(),
            1,
            new BigDecimal("1.0"),
            new BigDecimal("0.02"),
            new BigDecimal("0.05"),
            8760);
    VirtualExchange ex = new VirtualExchange(SYM, cfg, CandleInterval.ONE_HOUR);

    ex.onCandle(bar(0, 100, 100, 100, 100));
    ex.onSignal(signal(Direction.BUY));
    ex.onCandle(bar(1, 100, 100, 100, 100));
    Candle decisionBar = bar(2, 100, 110, 97, 100);
    List<Candle> corruptSubBars = List.of(subBar(2, 0, 100, 111, 99, 108)); // high=111 > 110

    assertThatThrownBy(() -> ex.onCandle(decisionBar, corruptSubBars))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("out of parent bar's range");
  }

  // ── PLAN-011 Task E / F5: sub-bar temporal-coverage guard ───────────────────────────────────

  @Test
  void subBars_outOfChronologicalOrder_failsLoudly() {
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.zero(),
            1,
            new BigDecimal("1.0"),
            new BigDecimal("0.02"),
            new BigDecimal("0.05"),
            8760);
    VirtualExchange ex = new VirtualExchange(SYM, cfg, CandleInterval.ONE_HOUR);

    ex.onCandle(bar(0, 100, 100, 100, 100));
    ex.onSignal(signal(Direction.BUY));
    ex.onCandle(bar(1, 100, 100, 100, 100));
    Candle decisionBar = bar(2, 100, 110, 97, 100);
    // 20 minutes in, then 0 minutes in -- out of order, even though both are individually within
    // the parent's price range and time window.
    List<Candle> outOfOrderSubBars =
        List.of(subBar(2, 20, 100, 105, 99, 102), subBar(2, 0, 100, 105, 99, 102));

    assertThatThrownBy(() -> ex.onCandle(decisionBar, outOfOrderSubBars))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not strictly ascending");
  }

  @Test
  void subBars_openBeforeParentOpen_failsLoudly() {
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.zero(),
            1,
            new BigDecimal("1.0"),
            new BigDecimal("0.02"),
            new BigDecimal("0.05"),
            8760);
    VirtualExchange ex = new VirtualExchange(SYM, cfg, CandleInterval.ONE_HOUR);

    ex.onCandle(bar(0, 100, 100, 100, 100));
    ex.onSignal(signal(Direction.BUY));
    ex.onCandle(bar(1, 100, 100, 100, 100));
    Candle decisionBar = bar(2, 100, 110, 97, 100); // opens at T0+2h
    // A sub-bar carrying bar 1's own timestamp (T0+1h) -- price-range-legal, but it belongs to the
    // previous decision bar's window, not this one -- indicates a mis-sliced series.
    List<Candle> leakedFromPreviousBar = List.of(subBar(1, 0, 100, 105, 99, 102));

    assertThatThrownBy(() -> ex.onCandle(decisionBar, leakedFromPreviousBar))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not lie within its parent bar's time window");
  }

  @Test
  void subBars_openAtOrAfterWindowEnd_failsLoudly() {
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.zero(),
            1,
            new BigDecimal("1.0"),
            new BigDecimal("0.02"),
            new BigDecimal("0.05"),
            8760);
    VirtualExchange ex = new VirtualExchange(SYM, cfg, CandleInterval.ONE_HOUR);

    ex.onCandle(bar(0, 100, 100, 100, 100));
    ex.onSignal(signal(Direction.BUY));
    ex.onCandle(bar(1, 100, 100, 100, 100));
    Candle decisionBar = bar(2, 100, 110, 97, 100); // window is [T0+2h, T0+3h)
    // A sub-bar carrying bar 3's own timestamp (T0+3h) -- exactly at the window's exclusive end,
    // so it belongs to the NEXT decision bar, not this one.
    List<Candle> leakedFromNextBar = List.of(subBar(3, 0, 100, 105, 99, 102));

    assertThatThrownBy(() -> ex.onCandle(decisionBar, leakedFromNextBar))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not lie within its parent bar's time window");
  }

  // ── F5: RM-gated SELL while flat fails loudly ───────────────────────────────────────────────

  @Test
  void onExecuteDecision_sellWhileFlat_throwsIllegalStateException() {
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 1, new BigDecimal("1.0"), null, null, 8760);
    VirtualExchange ex = new VirtualExchange(SYM, cfg, CandleInterval.ONE_HOUR);
    ExecutionDecision.Execute sellWhileFlat =
        new ExecutionDecision.Execute(
            SYM,
            Direction.SELL,
            new BigDecimal("1"),
            new BigDecimal("100"),
            new BigDecimal("101"),
            new BigDecimal("1"),
            Optional.empty(),
            "test",
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"));

    assertThatThrownBy(() -> ex.onExecuteDecision(sellWhileFlat))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RM must refuse upstream");
  }

  // ── End-of-data sweep ────────────────────────────────────────────────────────────────────────

  @Test
  void closeAll_marksOpenPositionAsEndOfData() {
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 1, new BigDecimal("1.0"), null, null, 8760);
    VirtualExchange ex = new VirtualExchange(SYM, cfg, CandleInterval.ONE_HOUR);

    ex.onCandle(bar(0, 100, 100, 100, 100));
    ex.onSignal(signal(Direction.BUY));
    Candle c1 = bar(1, 110, 110, 110, 110);
    ex.onCandle(c1);
    ex.closeAll(c1);

    assertThat(ex.trades()).hasSize(1);
    assertThat(ex.trades().get(0).exitReason()).isEqualTo(ExitReason.END_OF_DATA);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

  private static Candle bar(long offsetSec, double open, double high, double low, double close) {
    return new Candle(
        Instant.parse("2024-01-01T00:00:00Z").plusSeconds(offsetSec * 3600),
        BigDecimal.valueOf(open),
        BigDecimal.valueOf(high),
        BigDecimal.valueOf(low),
        BigDecimal.valueOf(close),
        BigDecimal.valueOf(1000));
  }

  /** A 1m-style sub-bar inside decision-bar {@code hourOffset}, {@code minuteOffset} in. */
  private static Candle subBar(
      long hourOffset, long minuteOffset, double open, double high, double low, double close) {
    return new Candle(
        Instant.parse("2024-01-01T00:00:00Z").plusSeconds(hourOffset * 3600 + minuteOffset * 60),
        BigDecimal.valueOf(open),
        BigDecimal.valueOf(high),
        BigDecimal.valueOf(low),
        BigDecimal.valueOf(close),
        BigDecimal.valueOf(50));
  }

  private static TechnicalSignal signal(Direction dir) {
    return new TechnicalSignal(
        SYM,
        dir,
        0.8,
        List.of(new Factor("TEST", "synthetic", 1.0)),
        "test signal",
        Instant.parse("2024-01-01T00:00:00Z"));
  }
}
