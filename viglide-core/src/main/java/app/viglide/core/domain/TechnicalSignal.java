package app.viglide.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic Layer-1 output produced by the statistical engine (CLAUDE.md §4, PRD §8). Every
 * field is validated at construction so downstream layers can trust the signal without re-checking.
 */
public record TechnicalSignal(
    String symbol,
    Direction direction,
    double confidence,
    List<Factor> factors,
    String explanation,
    Instant asOf) {

  /**
   * Validates fields and defensively copies the factor list. Confidence must be in [0, 1] because
   * it is used as a probability-like gate in the Decision Router (CLAUDE.md §4).
   */
  public TechnicalSignal {
    Objects.requireNonNull(symbol, "symbol");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(factors, "factors");
    Objects.requireNonNull(explanation, "explanation");
    Objects.requireNonNull(asOf, "asOf");
    if (symbol.isBlank()) throw new IllegalArgumentException("symbol must not be blank");
    if (confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be in [0,1], got: " + confidence);
    }
    if (explanation.isBlank()) throw new IllegalArgumentException("explanation must not be blank");
    factors = List.copyOf(factors);
  }
}
