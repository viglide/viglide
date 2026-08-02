package app.viglide.core.domain;

/**
 * What executing a {@link TargetPosition} means in terms of exchange legs (PLAN-015 Task A,
 * ADR-0018). {@link app.viglide.core.spi.StrategyKind} answers "what data does the strategy read";
 * this answers "what does acting on its target imply" — the two are deliberately not conflated
 * (e.g. a {@code FUNDING_AWARE} strategy's target is always {@link #DELTA_NEUTRAL_CARRY}, never
 * {@link #SPOT_ONLY}, regardless of what data it reads to get there). PLAN-014's {@code
 * HedgedExecutor} reads this to decide how many legs, and which side of each, a non-zero {@link
 * TargetPosition#targetWeight()} requires.
 */
public enum PositionShape {
  /**
   * Single spot leg, long-only. {@link TargetPosition#targetWeight()} must be {@code >= 0} — a
   * strategy declaring this shape is structurally incapable of shorting (enforced in {@link
   * TargetPosition}'s canonical constructor).
   */
  SPOT_LONG,

  /**
   * Single perpetual-futures leg, short-only, unhedged by any spot leg. {@link
   * TargetPosition#targetWeight()} must be {@code <= 0}. Distinct from {@link
   * #DELTA_NEUTRAL_CARRY}'s short leg, which is always paired with an offsetting spot long — this
   * shape carries real directional (price) risk.
   */
  PERP_SHORT,

  /**
   * The funding-carry basis trade: long spot + short perp, same notional, held as one economic unit
   * (ADR-0009). {@link TargetPosition#targetWeight()} must be {@code >= 0} — the size of the paired
   * trade, never negative. <strong>Exiting the trade is target weight {@code 0}, never a negative
   * weight</strong>: a negative weight would read as "reverse the pairing into a naked short,"
   * which is the exact misinterpretation {@code CLAUDE.md} §11 warns a {@code FUNDING_AWARE}
   * strategy's {@code SELL} must never become on the single-leg order path. This shape is what
   * makes that trap structurally harder to hit again once strategies migrate to {@link
   * app.viglide.core.spi.PortfolioStrategy}.
   */
  DELTA_NEUTRAL_CARRY,

  /**
   * Single, unhedged leg whose direction is given by the sign of {@link
   * TargetPosition#targetWeight()} (positive = long, negative = short) — no long/short-only
   * restriction. This is the shape {@link app.viglide.core.spi.PortfolioStrategy#ofSingle} assigns
   * to a wrapped {@code OHLCV}-kind {@link app.viglide.core.spi.TradingStrategy}: a plain
   * directional strategy with no declared multi-leg intent.
   */
  SPOT_ONLY
}
