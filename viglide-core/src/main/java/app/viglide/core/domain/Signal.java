package app.viglide.core.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A calibrated forecast, not a trade (ADR-0028): "on {@code pair}, over {@code horizon}, the
 * direction is {@code direction} with probability {@code probability}." Distinct from {@link
 * TechnicalSignal}, Layer 1's existing action-shaped output — a {@code Signal} carries no size, no
 * stop-loss, and is never routed to the Risk Manager or an order path. It is validated on
 * predictive content and calibration (K3, {@code app.viglide.research} — private {@code
 * viglide-lab} owns the harness), never on P&amp;L.
 *
 * <p>{@code direction} is never {@link Direction#HOLD}: "no view" is expressed by a {@link
 * app.viglide.core.spi.SignalStrategy} returning {@link java.util.Optional#empty()} for a given
 * context, not by a present {@code Signal} whose direction carries no forecast to calibrate
 * against.
 */
public record Signal(
    String pair, Duration horizon, Direction direction, double probability, Instant asOf) {

  /**
   * Validates fields so a miscalibrated or malformed signal can never propagate silently. {@code
   * probability} must be in {@code [0, 1]} — it is meant to be read literally as a calibrated
   * probability (ADR-0028 S2), not a loose confidence score.
   */
  public Signal {
    Objects.requireNonNull(pair, "pair");
    Objects.requireNonNull(horizon, "horizon");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(asOf, "asOf");
    if (pair.isBlank()) throw new IllegalArgumentException("pair must not be blank");
    if (horizon.isZero() || horizon.isNegative()) {
      throw new IllegalArgumentException("horizon must be positive, got: " + horizon);
    }
    if (direction == Direction.HOLD) {
      throw new IllegalArgumentException(
          "direction must not be HOLD -- a Signal with no view should not be emitted; return"
              + " Optional.empty() instead");
    }
    if (probability < 0.0 || probability > 1.0) {
      throw new IllegalArgumentException("probability must be in [0,1], got: " + probability);
    }
  }
}
