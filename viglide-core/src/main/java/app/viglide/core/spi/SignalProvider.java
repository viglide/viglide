package app.viglide.core.spi;

import java.util.Map;

/**
 * Discovery seam for {@link SignalStrategy} implementations — the signal-emitting counterpart of
 * {@link StrategyProvider}, same {@code META-INF/services} discovery shape and the same reason
 * (ADR-0025 §5.1): a module that resolves a signal by name needs no compile-time dependency on the
 * module that implements it.
 *
 * <p>Deliberately a separate provider/registry pair from {@link StrategyProvider}/{@link
 * StrategyRegistry} rather than a shared one: a {@link SignalStrategy} is a structurally different
 * artifact (it builds a {@link SignalStrategy}, not a {@link TradingStrategy}), and keeping the two
 * registries apart means nothing that resolves a tradeable strategy by name can ever resolve a
 * signal-only one by accident.
 */
public interface SignalProvider {

  /** Short signal-strategy name — the {@code --signal=} value. */
  String name();

  /** Identifies this signal strategy for audit and display purposes. */
  StrategyMetadata metadata();

  /**
   * Builds a fresh signal-strategy instance from a flat CLI-style argument map. Each provider
   * extracts the keys it cares about and falls back to its own defaults for the rest.
   */
  SignalStrategy create(Map<String, String> params);
}
