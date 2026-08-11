package app.viglide.research.k3;

import app.viglide.core.domain.Direction;
import java.time.Instant;
import java.util.Objects;

/**
 * One realized outcome of a fired {@link app.viglide.core.domain.Signal}: what the signal said, and
 * what actually happened over its horizon. The K3 measurement harness's only input type (ADR-0028,
 * PLAN-024 Task D) — deliberately decoupled from {@code MarketContext}/candle replay machinery so
 * the harness can be tested against synthetic data and reused for any {@link
 * app.viglide.core.spi.SignalStrategy}, rather than wired to one replay loop's own shape.
 */
public record SignalObservation(
    String pair,
    int oosYear,
    Instant asOf,
    double probability,
    Direction direction,
    double forwardReturn) {

  public SignalObservation {
    Objects.requireNonNull(pair, "pair");
    Objects.requireNonNull(asOf, "asOf");
    Objects.requireNonNull(direction, "direction");
    if (pair.isBlank()) throw new IllegalArgumentException("pair must not be blank");
    if (direction == Direction.HOLD) {
      throw new IllegalArgumentException("direction must not be HOLD (see Signal's Javadoc)");
    }
    if (probability < 0.0 || probability > 1.0) {
      throw new IllegalArgumentException("probability must be in [0,1], got: " + probability);
    }
  }

  /**
   * Directional edge realized by this observation: {@code forwardReturn} if the declared direction
   * was BUY, its negation if SELL — the return a trader betting the stated direction would actually
   * realize. Positive means the signal was directionally right.
   */
  public double directionalReturn() {
    return direction == Direction.BUY ? forwardReturn : -forwardReturn;
  }

  /** Whether the signal's direction was realized — the outcome S2's reliability curve bins on. */
  public boolean directionCorrect() {
    return directionalReturn() > 0.0;
  }
}
