package app.viglide.core.spi;

import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import java.util.Optional;

/**
 * The only contract that crosses the public/private module boundary (PRD §9.1, CLAUDE.md §2). The
 * public {@code viglide-core} defines this interface; private {@code viglide-strategies} provides
 * the implementations. The runtime wires them together via dependency injection.
 *
 * <p>Implementations <em>must</em> be deterministic and side-effect free: identical input must
 * always produce identical output, with no I/O, randomness, or LLM calls (NFR-7).
 */
public interface TradingStrategy {

  /**
   * Evaluates the market context and returns a signal if the context is sufficient.
   *
   * @return empty if the context cannot be evaluated (e.g. fewer candles than the lookback window
   *     requires); otherwise a present signal whose direction may be {@code HOLD}.
   */
  Optional<TechnicalSignal> evaluate(MarketContext context);

  /** Returns the metadata that identifies this strategy for audit and display purposes. */
  StrategyMetadata metadata();
}
