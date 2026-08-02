package app.viglide.core.domain;

/** Layer-1 directional verdict emitted by a strategy (PRD §10 FR-2). */
public enum Direction {
  BUY,
  SELL,
  /** Evaluated successfully but no actionable edge detected. */
  HOLD
}
