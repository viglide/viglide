package app.viglide.core.indicator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Bollinger Bands output triple aligned to a shared {@code offset}: every index {@code k} of {@code
 * middle}, {@code upper}, and {@code lower} corresponds to input price index {@code offset + k}. By
 * construction {@code lower[k] <= middle[k] <= upper[k]} at every index.
 */
public record BollingerSeries(
    int offset, List<BigDecimal> middle, List<BigDecimal> upper, List<BigDecimal> lower) {

  /** Validates {@code offset >= 0}, equal non-empty lengths, and defensively copies the lists. */
  public BollingerSeries {
    Objects.requireNonNull(middle, "middle");
    Objects.requireNonNull(upper, "upper");
    Objects.requireNonNull(lower, "lower");
    if (offset < 0) throw new IllegalArgumentException("offset must be >= 0, got: " + offset);
    if (middle.isEmpty()) throw new IllegalArgumentException("middle must not be empty");
    if (middle.size() != upper.size() || middle.size() != lower.size()) {
      throw new IllegalArgumentException(
          "middle, upper and lower must have equal length; got "
              + middle.size()
              + "/"
              + upper.size()
              + "/"
              + lower.size());
    }
    middle = List.copyOf(middle);
    upper = List.copyOf(upper);
    lower = List.copyOf(lower);
  }

  public BigDecimal lastMiddle() {
    return middle.getLast();
  }

  public BigDecimal lastUpper() {
    return upper.getLast();
  }

  public BigDecimal lastLower() {
    return lower.getLast();
  }

  /** Width of the band at the last index — proxy for current volatility. */
  public BigDecimal lastBandWidth() {
    return upper.getLast().subtract(lower.getLast());
  }
}
