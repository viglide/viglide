package app.viglide.core.spi;

import java.util.Map;

/**
 * Discovery seam between the engine and whoever supplies strategies. Implementations are registered
 * via {@code META-INF/services} and resolved by name at runtime, so no module that *runs*
 * strategies needs a compile-time dependency on a module that *implements* them. This is what lets
 * the public runtime stay free of the private strategy set (ADR-0025 §5.1).
 */
public interface StrategyProvider {

  /**
   * Short strategy name, e.g. {@code "emarsi"}, {@code "fundingarb"} — the {@code --strategy=}
   * value.
   */
  String name();

  /** Identifies this strategy for audit and display purposes. */
  StrategyMetadata metadata();

  /**
   * Builds a fresh strategy instance from a flat CLI-style argument map. Each provider extracts the
   * keys it cares about and falls back to its own textbook defaults for the rest.
   */
  TradingStrategy create(Map<String, String> params);
}
