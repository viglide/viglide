package app.viglide.core.backtest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One sample on the equity curve: portfolio value at a given bar's close. {@code equity} includes
 * both cash and mark-to-market value of any open position.
 */
public record EquityPoint(Instant at, BigDecimal equity) {
  public EquityPoint {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(equity, "equity");
  }
}
