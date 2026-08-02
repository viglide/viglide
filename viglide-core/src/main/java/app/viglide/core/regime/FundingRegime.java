package app.viglide.core.regime;

/**
 * Whether a pair's funding-rate carry is currently rich or compressed relative to its own long-run
 * history (PLAN-009 Task H). {@code UNKNOWN} when there isn't enough trailing history to label a
 * period — never guessed.
 */
public enum FundingRegime {
  /** Trailing-30d median |funding rate| is above the pair's own full-history median. */
  RICH,
  /** Trailing-30d median |funding rate| is at or below the pair's own full-history median. */
  COMPRESSED,
  /** Fewer than {@link RegimeLabeler#MIN_FUNDING_EVENTS_FOR_LABEL} funding events in the window. */
  UNKNOWN
}
