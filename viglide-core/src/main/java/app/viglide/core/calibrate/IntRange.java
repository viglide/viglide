package app.viglide.core.calibrate;

/**
 * Inclusive integer interval with a fixed step. {@code IntRange(5, 15, 1)} ⇒ {5, 6, …, 15} (11
 * values). Used by {@link app.viglide.core.spi.ParameterSpaceProvider} to enumerate sweep
 * dimensions.
 */
public record IntRange(int min, int max, int step) {

  /** Validates {@code min <= max} and {@code step >= 1}. */
  public IntRange {
    if (step < 1) throw new IllegalArgumentException("step must be >= 1, got: " + step);
    if (min > max) {
      throw new IllegalArgumentException("min (" + min + ") must be <= max (" + max + ")");
    }
  }

  /** Number of discrete values in this range. */
  public int size() {
    return (max - min) / step + 1;
  }

  /** Returns the {@code i}-th value, where {@code 0 <= i < size()}. */
  public int at(int i) {
    return min + i * step;
  }
}
