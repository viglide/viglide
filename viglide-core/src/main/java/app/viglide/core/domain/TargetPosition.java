package app.viglide.core.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * A {@link app.viglide.core.spi.PortfolioStrategy}'s desired allocation for one symbol (PLAN-015
 * Task A, ADR-0018): a <em>target</em>, not a {@code BUY}/{@code SELL} action. {@code targetWeight}
 * is the fraction of the strategy's allocated capital this symbol should occupy, in {@code [-1, 1]}
 * — positive is long/carry-on, negative is short, {@code 0} is flat.
 *
 * <p><strong>Absence means flat.</strong> A symbol missing from a {@link
 * app.viglide.core.spi.PortfolioStrategy#evaluate} result is equivalent to that symbol appearing
 * with {@code targetWeight} {@code 0} — both mean "this strategy wants no position here." This is
 * what lets a ranking strategy (PLAN-015 Task E) return only its top-/bottom-k without enumerating
 * every excluded symbol at zero, while still correctly closing a symbol that rotates out of the
 * selection on a later bar. There is no third "no opinion, leave whatever is currently held alone"
 * state — expressing a target is an unconditional statement of desired book composition each time
 * it is evaluated, which is what makes rebalancing toward it (PLAN-015 Task B) well-defined.
 *
 * <p>Expressing intent as a target rather than an action is what lets one strategy describe an
 * entire book in one call, and it is what makes the Risk Manager's job well-defined at the
 * portfolio level (PLAN-015 Task B): it scales every target down by a common factor when the whole
 * book breaches a limit, rather than approving or refusing individual symbols in isolation.
 */
public record TargetPosition(
    String symbol,
    BigDecimal targetWeight,
    PositionShape shape,
    List<Factor> factors,
    String explanation) {

  private static final BigDecimal MAX_WEIGHT = BigDecimal.ONE;
  private static final BigDecimal MIN_WEIGHT = BigDecimal.ONE.negate();

  /**
   * Validates fields, defensively copies {@code factors}, and enforces the sign a symbol's {@link
   * PositionShape} allows — see {@link PositionShape}'s Javadoc for why {@link
   * PositionShape#DELTA_NEUTRAL_CARRY} in particular must never go negative.
   */
  public TargetPosition {
    Objects.requireNonNull(symbol, "symbol");
    Objects.requireNonNull(targetWeight, "targetWeight");
    Objects.requireNonNull(shape, "shape");
    Objects.requireNonNull(factors, "factors");
    Objects.requireNonNull(explanation, "explanation");
    if (symbol.isBlank()) throw new IllegalArgumentException("symbol must not be blank");
    if (targetWeight.compareTo(MIN_WEIGHT) < 0 || targetWeight.compareTo(MAX_WEIGHT) > 0) {
      throw new IllegalArgumentException("targetWeight must be in [-1,1], got: " + targetWeight);
    }
    if (explanation.isBlank()) throw new IllegalArgumentException("explanation must not be blank");
    switch (shape) {
      case SPOT_LONG, DELTA_NEUTRAL_CARRY -> {
        if (targetWeight.signum() < 0) {
          throw new IllegalArgumentException(
              shape + " requires targetWeight >= 0, got: " + targetWeight);
        }
      }
      case PERP_SHORT -> {
        if (targetWeight.signum() > 0) {
          throw new IllegalArgumentException(
              shape + " requires targetWeight <= 0, got: " + targetWeight);
        }
      }
      case SPOT_ONLY -> {
        // No sign restriction: direction is whatever the sign of targetWeight says.
      }
    }
    factors = List.copyOf(factors);
  }
}
