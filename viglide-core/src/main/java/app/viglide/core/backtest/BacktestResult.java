package app.viglide.core.backtest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome of a single backtest run. {@code sharpe} and {@code winRate} are unitless doubles;
 * monetary quantities ({@code totalReturn}, {@code maxDrawdown}) use {@link BigDecimal} per
 * CLAUDE.md §5. {@code diagnostics} is a free-form map for run-specific debugging info (parameter
 * snapshot, bar counts, last evaluation timestamp, …) — keep it out of any equality comparison.
 *
 * <p>{@code totalReturn} is expressed as a fraction (e.g. {@code 0.07} ⇒ +7%). {@code maxDrawdown}
 * is also a fraction and is always non-negative.
 */
public record BacktestResult(
    BigDecimal startingEquity,
    BigDecimal endingEquity,
    BigDecimal totalReturn,
    double sharpe,
    BigDecimal maxDrawdown,
    double winRate,
    int tradeCount,
    List<Trade> trades,
    List<EquityPoint> equityCurve,
    Map<String, Object> diagnostics,
    List<Refusal> refusals) {

  /** Defensively copies the collections so the result is immutable. */
  public BacktestResult {
    Objects.requireNonNull(startingEquity, "startingEquity");
    Objects.requireNonNull(endingEquity, "endingEquity");
    Objects.requireNonNull(totalReturn, "totalReturn");
    Objects.requireNonNull(maxDrawdown, "maxDrawdown");
    Objects.requireNonNull(trades, "trades");
    Objects.requireNonNull(equityCurve, "equityCurve");
    Objects.requireNonNull(diagnostics, "diagnostics");
    Objects.requireNonNull(refusals, "refusals");
    trades = List.copyOf(trades);
    equityCurve = List.copyOf(equityCurve);
    diagnostics = Map.copyOf(diagnostics);
    refusals = List.copyOf(refusals);
  }

  /** Backward-compatible constructor for callers that predate the refusal audit trail (F6). */
  public BacktestResult(
      BigDecimal startingEquity,
      BigDecimal endingEquity,
      BigDecimal totalReturn,
      double sharpe,
      BigDecimal maxDrawdown,
      double winRate,
      int tradeCount,
      List<Trade> trades,
      List<EquityPoint> equityCurve,
      Map<String, Object> diagnostics) {
    this(
        startingEquity,
        endingEquity,
        totalReturn,
        sharpe,
        maxDrawdown,
        winRate,
        tradeCount,
        trades,
        equityCurve,
        diagnostics,
        List.of());
  }
}
