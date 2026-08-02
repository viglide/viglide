package app.viglide.core.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One OHLCV candle. All price and volume fields are {@link BigDecimal} so money arithmetic stays
 * exact (CLAUDE.md §5). Validated eagerly so invalid market data never propagates.
 */
public record Candle(
    Instant openTime,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    BigDecimal volume) {

  /** Validates invariants: non-null fields, non-negative prices/volume, high ≥ low. */
  public Candle {
    Objects.requireNonNull(openTime, "openTime");
    Objects.requireNonNull(open, "open");
    Objects.requireNonNull(high, "high");
    Objects.requireNonNull(low, "low");
    Objects.requireNonNull(close, "close");
    Objects.requireNonNull(volume, "volume");
    if (open.signum() < 0) throw new IllegalArgumentException("open must be >= 0");
    if (high.signum() < 0) throw new IllegalArgumentException("high must be >= 0");
    if (low.signum() < 0) throw new IllegalArgumentException("low must be >= 0");
    if (close.signum() < 0) throw new IllegalArgumentException("close must be >= 0");
    if (volume.signum() < 0) throw new IllegalArgumentException("volume must be >= 0");
    if (high.compareTo(low) < 0) throw new IllegalArgumentException("high must be >= low");
  }
}
