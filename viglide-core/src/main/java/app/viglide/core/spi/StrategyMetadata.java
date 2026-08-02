package app.viglide.core.spi;

import java.util.Objects;

/** Identifies a strategy implementation for logging, audit trails, and the UI (PRD §9.1). */
public record StrategyMetadata(
    String name, String version, String description, StrategyKind kind, StrategyStatus status) {

  /** All fields must be non-blank so metadata is always auditable. */
  public StrategyMetadata {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(status, "status");
    if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
    if (version.isBlank()) throw new IllegalArgumentException("version must not be blank");
    if (description.isBlank()) throw new IllegalArgumentException("description must not be blank");
  }

  /**
   * Backward-compatible 4-argument constructor for strategies written before {@link StrategyStatus}
   * existed (PLAN-015 Task G). Defaults {@link #status()} to {@link StrategyStatus#CANDIDATE} —
   * every strategy's prior eligibility is unchanged unless its {@code metadata()} is explicitly
   * updated to say otherwise.
   */
  public StrategyMetadata(String name, String version, String description, StrategyKind kind) {
    this(name, version, description, kind, StrategyStatus.CANDIDATE);
  }

  /**
   * Backward-compatible 3-argument constructor for OHLCV strategies (the common case). Defaults
   * {@link #kind()} to {@link StrategyKind#OHLCV} (F10, PLAN-007 Task D) and {@link #status()} to
   * {@link StrategyStatus#CANDIDATE}.
   */
  public StrategyMetadata(String name, String version, String description) {
    this(name, version, description, StrategyKind.OHLCV, StrategyStatus.CANDIDATE);
  }
}
