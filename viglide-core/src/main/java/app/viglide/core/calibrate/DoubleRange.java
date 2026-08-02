package app.viglide.core.calibrate;

/**
 * Inclusive double interval with a fixed step. {@code DoubleRange(0.001, 0.05, 0.001)} ⇒ 50 values.
 * Used by {@link app.viglide.core.spi.ParameterSpaceProvider} to enumerate sweep dimensions.
 */
public record DoubleRange(double min, double max, double step) {

  /** Validates {@code min <= max} and {@code step > 0}. */
  public DoubleRange {
    if (step <= 0.0) throw new IllegalArgumentException("step must be > 0, got: " + step);
    if (min > max) {
      throw new IllegalArgumentException("min (" + min + ") must be <= max (" + max + ")");
    }
  }

  /** Number of discrete values in this range, inclusive of both endpoints. */
  public int size() {
    return (int) Math.round((max - min) / step) + 1;
  }

  /** Returns the {@code i}-th value, where {@code 0 <= i < size()}. */
  public double at(int i) {
    return Math.min(max, min + i * step);
  }
}
