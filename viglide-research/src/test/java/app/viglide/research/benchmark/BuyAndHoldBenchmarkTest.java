package app.viglide.research.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import app.viglide.core.backtest.FeeModel;
import app.viglide.core.domain.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** PLAN-016 Task C: the equal-weight buy-and-hold benchmark the K1′ verdict compares against. */
class BuyAndHoldBenchmarkTest {

  private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
  private static final BigDecimal CASH = new BigDecimal("10000");

  @Test
  void zeroFeeFlatPriceReturnsExactlyZero() {
    var r =
        BuyAndHoldBenchmark.run(
            universe(Map.of("AAA", new double[] {100, 100, 100})), CASH, FeeModel.zero());

    assertThat(r.totalReturn()).usingComparator(BigDecimal::compareTo).isEqualTo(BigDecimal.ZERO);
    assertThat(r.feesPaid()).usingComparator(BigDecimal::compareTo).isEqualTo(BigDecimal.ZERO);
    assertThat(r.symbolsHeld()).isEqualTo(1);
  }

  @Test
  void zeroFeeDoublingPriceReturnsExactlyOneHundredPercent() {
    var r =
        BuyAndHoldBenchmark.run(
            universe(Map.of("AAA", new double[] {100, 150, 200})), CASH, FeeModel.zero());

    assertThat(r.totalReturn().doubleValue()).isCloseTo(1.0, within(1e-12));
  }

  @Test
  void equalWeightsTheBasketRatherThanTheSharePrices() {
    // A 5x on a cheap coin and a flat on an expensive one must give +200%, not something weighted
    // by price level -- equal *capital*, not equal quantity.
    var r =
        BuyAndHoldBenchmark.run(
            universe(
                Map.of(
                    "CHEAP", new double[] {1, 3, 5},
                    "DEAR", new double[] {40000, 40000, 40000})),
            CASH,
            FeeModel.zero());

    assertThat(r.totalReturn().doubleValue()).isCloseTo(2.0, within(1e-9));
    assertThat(r.returnBySymbol().get("CHEAP").doubleValue()).isCloseTo(4.0, within(1e-9));
    assertThat(r.returnBySymbol().get("DEAR").doubleValue()).isCloseTo(0.0, within(1e-9));
  }

  @Test
  void chargesExactlyOneEntryAndOneExitFee() {
    // Flat price, taker fees: the only thing that can move equity is the two fees. 10 bps each
    // (5 taker + 5 slippage), applied to the sleeve on entry and to the proceeds on exit.
    var r =
        BuyAndHoldBenchmark.run(
            universe(Map.of("AAA", new double[] {100, 100, 100})), CASH, FeeModel.taker());

    double adverse = 0.0010;
    double expected = (1 - adverse) * (1 - adverse) - 1; // entry then exit, no price move
    assertThat(r.totalReturn().doubleValue()).isCloseTo(expected, within(1e-12));
    assertThat(r.feesPaid().doubleValue()).isGreaterThan(0);
  }

  @Test
  void producesAnEquityCurveUsableByTheSameMetricsAsAStrategyRun() {
    var r =
        BuyAndHoldBenchmark.run(
            universe(Map.of("AAA", new double[] {100, 50, 100})), CASH, FeeModel.zero());

    assertThat(r.equityCurve()).hasSize(3);
    // The mid-run halving must be visible in the curve -- this is the whole reason the benchmark
    // emits one, since ADR-0016 condition 3 is stated in ulcerIndex.
    assertThat(r.equityCurve().get(1).equity().doubleValue()).isCloseTo(5000.0, within(1e-6));
    assertThat(r.equityCurve().getFirst().equity().doubleValue()).isCloseTo(10000.0, within(1e-6));
  }

  @Test
  void aSymbolThatListsLateIsHeldFlatAtCostRatherThanMarkedToZero() {
    // LATE starts two bars in. Marking it at zero until then would print a ~50% drawdown the
    // basket never took and make the ulcer index unusable on any staggered universe.
    Map<String, List<Candle>> u = new LinkedHashMap<>();
    u.put("EARLY", candles(new double[] {100, 100, 100, 100}, 0));
    u.put("LATE", candles(new double[] {100, 100}, 2));

    var r = BuyAndHoldBenchmark.run(u, CASH, FeeModel.zero());

    assertThat(r.equityCurve()).hasSize(4);
    for (int i = 0; i < 3; i++) {
      assertThat(r.equityCurve().get(i).equity().doubleValue())
          .as("equity at index %d", i)
          .isCloseTo(10000.0, within(1e-6));
    }
  }

  @Test
  void isInvariantToTheCallersMapIterationOrder() {
    Map<String, List<Candle>> forward = new LinkedHashMap<>();
    forward.put("AAA", candles(new double[] {100, 137}, 0));
    forward.put("BBB", candles(new double[] {7, 3}, 0));
    forward.put("CCC", candles(new double[] {1234, 1300}, 0));
    Map<String, List<Candle>> reversed = new LinkedHashMap<>();
    reversed.put("CCC", forward.get("CCC"));
    reversed.put("BBB", forward.get("BBB"));
    reversed.put("AAA", forward.get("AAA"));

    assertThat(BuyAndHoldBenchmark.run(reversed, CASH, FeeModel.taker()).totalReturn())
        .isEqualByComparingTo(
            BuyAndHoldBenchmark.run(forward, CASH, FeeModel.taker()).totalReturn());
  }

  @Test
  void emptySeriesAreExcludedFromTheSplitRatherThanAllocatedZero() {
    Map<String, List<Candle>> u = new LinkedHashMap<>();
    u.put("REAL", candles(new double[] {100, 200}, 0));
    u.put("EMPTY", List.of());

    var r = BuyAndHoldBenchmark.run(u, CASH, FeeModel.zero());

    // If EMPTY had taken half the capital and sat in cash, the return would be +50%.
    assertThat(r.symbolsHeld()).isEqualTo(1);
    assertThat(r.totalReturn().doubleValue()).isCloseTo(1.0, within(1e-9));
  }

  @Test
  void rejectsNonsenseCapital() {
    assertThatThrownBy(
            () ->
                BuyAndHoldBenchmark.run(
                    universe(Map.of("AAA", new double[] {1, 2})), BigDecimal.ZERO, FeeModel.zero()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("startingCash must be > 0");
  }

  @Test
  void anAllEmptyUniverseIsEmptyRatherThanAnException() {
    var r = BuyAndHoldBenchmark.run(Map.of("A", List.of(), "B", List.of()), CASH, FeeModel.taker());

    assertThat(r.symbolsHeld()).isZero();
    assertThat(r.equityCurve()).isEmpty();
    assertThat(r.totalReturn()).usingComparator(BigDecimal::compareTo).isEqualTo(BigDecimal.ZERO);
  }

  private static Map<String, List<Candle>> universe(Map<String, double[]> closes) {
    Map<String, List<Candle>> out = new LinkedHashMap<>();
    closes.forEach((symbol, series) -> out.put(symbol, candles(series, 0)));
    return out;
  }

  private static List<Candle> candles(double[] closes, int startIndex) {
    List<Candle> out = new ArrayList<>(closes.length);
    for (int i = 0; i < closes.length; i++) {
      BigDecimal c = BigDecimal.valueOf(closes[i]);
      out.add(new Candle(T0.plusSeconds(3600L * (startIndex + i)), c, c, c, c, BigDecimal.ONE));
    }
    return out;
  }
}
