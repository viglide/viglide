package app.viglide.core.backtest;

import app.viglide.core.domain.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One round-trip trade recorded by the virtual exchange. Open and close prices include simulated
 * slippage and fees — {@code pnl} is the realised cash delta the strategy actually captured.
 */
public record Trade(
    Instant entryTime,
    Instant exitTime,
    Direction side,
    BigDecimal entryPrice,
    BigDecimal exitPrice,
    BigDecimal size,
    BigDecimal pnl,
    ExitReason exitReason) {

  /** Validates non-null fields and a non-zero side (HOLD is not a trade direction). */
  public Trade {
    Objects.requireNonNull(entryTime, "entryTime");
    Objects.requireNonNull(exitTime, "exitTime");
    Objects.requireNonNull(side, "side");
    Objects.requireNonNull(entryPrice, "entryPrice");
    Objects.requireNonNull(exitPrice, "exitPrice");
    Objects.requireNonNull(size, "size");
    Objects.requireNonNull(pnl, "pnl");
    Objects.requireNonNull(exitReason, "exitReason");
    if (side == Direction.HOLD) throw new IllegalArgumentException("trade side cannot be HOLD");
    if (entryPrice.signum() <= 0) throw new IllegalArgumentException("entryPrice must be > 0");
    if (exitPrice.signum() <= 0) throw new IllegalArgumentException("exitPrice must be > 0");
    if (size.signum() <= 0) throw new IllegalArgumentException("size must be > 0");
  }
}
