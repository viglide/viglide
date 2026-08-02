package app.viglide.core.risk;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of the portfolio at the moment of a risk gate call (PLAN-005b, ADR-0006). The
 * Risk Manager reads this to enforce position-size, leverage, and drawdown limits.
 *
 * <p>{@code openPositions} maps symbol to position <strong>notional</strong> value in quote
 * currency (e.g. {@code {"BTCUSDT": 200.00}} for a $200 BTC position). Empty when flat.
 */
public record PortfolioState(
    BigDecimal cash,
    BigDecimal equity,
    BigDecimal peakEquity,
    Map<String, BigDecimal> openPositions,
    boolean circuitBreakerTripped,
    /**
     * Equity at the start of the current UTC day, if the caller tracks a day boundary (PLAN-012
     * Task F). {@link Optional#empty()} for any caller that doesn't (every backtest harness) —
     * {@link RiskManager#gate}'s daily-loss check only fires when both this <em>and</em> {@link
     * RiskParameters#maxDailyLossAbs()} are present, so an absent day boundary is a no-op, not a
     * fail-safe refusal (this is an opt-in extra, not a required risk input).
     */
    Optional<BigDecimal> dayStartEquity) {

  /** Validates invariants and defensively copies the positions map. */
  public PortfolioState {
    Objects.requireNonNull(cash, "cash");
    Objects.requireNonNull(equity, "equity");
    Objects.requireNonNull(peakEquity, "peakEquity");
    Objects.requireNonNull(openPositions, "openPositions");
    Objects.requireNonNull(dayStartEquity, "dayStartEquity");
    if (equity.signum() < 0) {
      throw new IllegalArgumentException("equity must be >= 0, got: " + equity);
    }
    if (peakEquity.signum() <= 0) {
      throw new IllegalArgumentException("peakEquity must be > 0, got: " + peakEquity);
    }
    if (peakEquity.compareTo(equity) < 0) {
      throw new IllegalArgumentException(
          "peakEquity (" + peakEquity + ") must be >= equity (" + equity + ")");
    }
    if (dayStartEquity.isPresent() && dayStartEquity.get().signum() <= 0) {
      throw new IllegalArgumentException(
          "dayStartEquity must be > 0 when present, got: " + dayStartEquity.get());
    }
    openPositions = Map.copyOf(openPositions);
  }

  /** Starting state: all cash, no positions, circuit breaker off, no day boundary tracked. */
  public static PortfolioState initial(BigDecimal startingCash) {
    Objects.requireNonNull(startingCash, "startingCash");
    if (startingCash.signum() <= 0) {
      throw new IllegalArgumentException("startingCash must be > 0");
    }
    return new PortfolioState(
        startingCash, startingCash, startingCash, Map.of(), false, Optional.empty());
  }

  /** Total notional across all open positions (sum of values in {@link #openPositions()}). */
  public BigDecimal totalOpenNotional() {
    return openPositions.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
