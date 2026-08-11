package app.viglide.research.benchmark;

import app.viglide.core.backtest.EquityPoint;
import app.viglide.core.backtest.FeeModel;
import app.viglide.core.domain.Candle;
import app.viglide.core.indicator.IndicatorMath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Equal-weight spot buy-and-hold over a symbol universe — the "did any of this beat simply owning
 * the basket?" benchmark that ADR-0016's verdict needs and that this project has never had outside
 * a disabled JUnit spike.
 *
 * <p><strong>Why it is built here rather than reused.</strong> The only prior implementation is
 * {@code Plan015TaskESpike.buyAndHoldReturn}, which averages each symbol's first-to-last close
 * return. That answers one question ("what did the basket return?") and cannot answer the two the
 * verdict actually asks — it charges no fees, so it flatters itself against a strategy that pays
 * them, and it produces no equity curve, so it cannot be compared on {@code ulcerIndex} or {@code
 * maxDrawdown}, which are ADR-0016 condition 3's own currency. A benchmark that is only comparable
 * on one of the three reported metrics invites the comparison to be made on whichever one happens
 * to be favourable.
 *
 * <p><strong>Deliberately the most generous honest baseline.</strong> Capital is split equally at
 * the first bar, held untouched, and liquidated at the last. It pays exactly one entry fee and one
 * exit fee per symbol and never rebalances — real buy-and-hold, not a periodically-rebalanced index
 * whose turnover would need its own cost assumptions. If a strategy cannot beat this, the deficit
 * is not a cost-modelling artefact.
 *
 * <p><strong>No survivorship correction, by construction.</strong> The universe is whatever the
 * caller passes. Against the 12-pair 2021-2026H1 corpus that universe was chosen in 2021 with
 * knowledge of which pairs still existed in 2026, so the benchmark inherits that bias — and it
 * inherits it identically to the strategies measured against it, which is the point. It is
 * <em>not</em> a claim about what a 2021 investor could have picked.
 */
public final class BuyAndHoldBenchmark {

  private BuyAndHoldBenchmark() {}

  /**
   * Result of one buy-and-hold run.
   *
   * @param equityCurve mark-to-market equity at every timestamp in the union of all symbols' bars,
   *     each symbol forward-filled from its last known close — feed to {@code
   *     EconomicMetrics.ulcerIndex} / {@code Metrics.maxDrawdown} exactly as a strategy's curve
   * @param totalReturn final equity over starting cash, minus one
   * @param feesPaid entry plus exit fees across all symbols
   * @param symbolsHeld how many of the requested symbols contributed a position
   * @param returnBySymbol per-symbol first-to-last close return, gross of fees — the diagnostic
   *     that says whether a basket result is broad or carried by one name
   */
  public record BuyAndHoldResult(
      List<EquityPoint> equityCurve,
      BigDecimal totalReturn,
      BigDecimal feesPaid,
      int symbolsHeld,
      Map<String, BigDecimal> returnBySymbol) {}

  /**
   * Runs the benchmark.
   *
   * @param spotBySymbol spot candles per symbol, ascending by {@code openTime}; empty series are
   *     skipped and excluded from the equal-weight split rather than silently allocated zero
   * @param startingCash total capital, split equally across the symbols that have data
   * @param fees cost model; both legs are charged {@link FeeModel#totalAdverseFactor} once, the
   *     same rate the strategy harnesses charge, so the comparison is like-for-like
   */
  public static BuyAndHoldResult run(
      Map<String, List<Candle>> spotBySymbol, BigDecimal startingCash, FeeModel fees) {
    Objects.requireNonNull(spotBySymbol, "spotBySymbol");
    Objects.requireNonNull(startingCash, "startingCash");
    Objects.requireNonNull(fees, "fees");
    if (startingCash.signum() <= 0) {
      throw new IllegalArgumentException("startingCash must be > 0, got: " + startingCash);
    }

    // Sorted, not the caller's iteration order: the equal-weight split and the fee accumulation are
    // BigDecimal sums, and BigDecimal addition at a bounded MathContext is not associative. An
    // unsorted walk would make the result depend on how the caller built its map (NFR-7).
    TreeSet<String> symbols = new TreeSet<>();
    for (var e : spotBySymbol.entrySet()) {
      if (e.getValue() != null && !e.getValue().isEmpty()) {
        symbols.add(e.getKey());
      }
    }
    if (symbols.isEmpty()) {
      return new BuyAndHoldResult(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, 0, Map.of());
    }

    BigDecimal adverse = fees.totalAdverseFactor(IndicatorMath.MC);
    BigDecimal perSymbolCash =
        startingCash.divide(BigDecimal.valueOf(symbols.size()), IndicatorMath.MC);

    Map<String, BigDecimal> quantity = new HashMap<>();
    Map<String, BigDecimal> returnBySymbol = new TreeMap<>();
    BigDecimal feesPaid = BigDecimal.ZERO;

    for (String symbol : symbols) {
      List<Candle> candles = spotBySymbol.get(symbol);
      BigDecimal entry = candles.getFirst().close();
      BigDecimal last = candles.getLast().close();
      // The entry fee comes out of the sleeve before it buys anything, so the position is what the
      // cash actually purchases -- not a notional the book could not have afforded.
      BigDecimal entryFee = perSymbolCash.multiply(adverse, IndicatorMath.MC);
      BigDecimal deployed = perSymbolCash.subtract(entryFee);
      quantity.put(symbol, deployed.divide(entry, IndicatorMath.MC));
      feesPaid = feesPaid.add(entryFee, IndicatorMath.MC);
      returnBySymbol.put(symbol, last.subtract(entry).divide(entry, IndicatorMath.MC));
    }

    TreeSet<Instant> allTimes = new TreeSet<>();
    for (String symbol : symbols) {
      for (Candle c : spotBySymbol.get(symbol)) {
        allTimes.add(c.openTime());
      }
    }

    Map<String, Integer> cursor = new HashMap<>();
    Map<String, BigDecimal> lastClose = new HashMap<>();
    for (String symbol : symbols) {
      cursor.put(symbol, 0);
      // Before a symbol's series starts it is marked at its own first close, i.e. held flat at
      // cost.
      // Marking it at zero instead would print a fake drawdown on any universe whose listings are
      // staggered, and the ulcer index would read that as risk the basket never took.
      lastClose.put(symbol, spotBySymbol.get(symbol).getFirst().close());
    }

    List<EquityPoint> curve = new ArrayList<>(allTimes.size());
    for (Instant t : allTimes) {
      BigDecimal equity = BigDecimal.ZERO;
      for (String symbol : symbols) {
        List<Candle> candles = spotBySymbol.get(symbol);
        int i = cursor.get(symbol);
        while (i < candles.size() && !candles.get(i).openTime().isAfter(t)) {
          lastClose.put(symbol, candles.get(i).close());
          i++;
        }
        cursor.put(symbol, i);
        equity =
            equity.add(
                quantity.get(symbol).multiply(lastClose.get(symbol), IndicatorMath.MC),
                IndicatorMath.MC);
      }
      curve.add(new EquityPoint(t, equity));
    }

    // Liquidate at the last bar: the strategy curves this is compared against are flat at the end,
    // so a benchmark that never pays to get out would be measured on a position it still holds.
    BigDecimal grossFinal = curve.getLast().equity();
    BigDecimal exitFee = grossFinal.multiply(adverse, IndicatorMath.MC);
    feesPaid = feesPaid.add(exitFee, IndicatorMath.MC);
    BigDecimal netFinal = grossFinal.subtract(exitFee);
    curve.set(curve.size() - 1, new EquityPoint(curve.getLast().at(), netFinal));

    BigDecimal totalReturn = netFinal.subtract(startingCash).divide(startingCash, IndicatorMath.MC);
    return new BuyAndHoldResult(
        List.copyOf(curve), totalReturn, feesPaid, symbols.size(), Map.copyOf(returnBySymbol));
  }
}
