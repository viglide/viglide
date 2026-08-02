package app.viglide.core.indicator;

import java.math.MathContext;

/**
 * Single source of truth for indicator rounding (ADR-0001 §3). All {@link java.math.BigDecimal}
 * division in indicators must use this {@link MathContext} so computations are bit-for-bit
 * reproducible across runs and environments (NFR-7).
 */
public final class IndicatorMath {

  private IndicatorMath() {}

  /** 16-digit decimal precision with HALF_EVEN rounding — matches IEEE 754 DECIMAL64. */
  public static final MathContext MC = MathContext.DECIMAL64;
}
