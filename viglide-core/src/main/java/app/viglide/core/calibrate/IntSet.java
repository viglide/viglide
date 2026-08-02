package app.viglide.core.calibrate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Explicit, non-uniform discrete set of integer values — e.g. {@code minHoldBars ∈ {0, 8, 24, 48,
 * 72}} (PLAN-008 Task F) — unlike {@link IntRange}'s fixed-step enumeration.
 */
public record IntSet(List<Integer> values) {

  public IntSet {
    Objects.requireNonNull(values, "values");
    if (values.isEmpty()) {
      throw new IllegalArgumentException("values must be non-empty");
    }
    values = List.copyOf(values);
  }

  public static IntSet of(int... values) {
    List<Integer> list = new ArrayList<>(values.length);
    for (int v : values) list.add(v);
    return new IntSet(list);
  }

  /** Number of discrete values in this set. */
  public int size() {
    return values.size();
  }

  /** Returns the {@code i}-th value, where {@code 0 <= i < size()}. */
  public int at(int i) {
    return values.get(i);
  }
}
