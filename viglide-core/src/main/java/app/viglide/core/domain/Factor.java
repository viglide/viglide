package app.viglide.core.domain;

import java.util.Objects;

/**
 * One contributing reason behind a signal, surfaced to the user as the "Signal Breakdown" (PRD §4).
 * Carries a human-readable code, a detail string, and a normalised contribution score in [0, 1]
 * that indicates how strongly this factor drove the signal.
 */
public record Factor(String code, String detail, double contribution) {

  /** Validates non-blank fields and contribution in [0, 1]. */
  public Factor {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(detail, "detail");
    if (code.isBlank()) throw new IllegalArgumentException("code must not be blank");
    if (detail.isBlank()) throw new IllegalArgumentException("detail must not be blank");
    if (contribution < 0.0 || contribution > 1.0) {
      throw new IllegalArgumentException("contribution must be in [0,1], got: " + contribution);
    }
  }
}
