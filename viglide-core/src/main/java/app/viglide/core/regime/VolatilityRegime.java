package app.viglide.core.regime;

/**
 * Trailing-30d realized-volatility tercile, relative to the pair's own full-history distribution of
 * trailing-30d volatility readings (PLAN-009 Task H). {@code UNKNOWN} when there isn't enough
 * trailing history to label a period — never guessed.
 */
public enum VolatilityRegime {
  /** Bottom tercile of the pair's own trailing-30d volatility distribution. */
  LOW,
  /** Middle tercile. */
  MEDIUM,
  /** Top tercile. */
  HIGH,
  /** Fewer than {@link RegimeLabeler#MIN_CANDLES_FOR_LABEL} hourly candles in the window. */
  UNKNOWN
}
