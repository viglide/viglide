package app.viglide.core.spi;

import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.Signal;
import java.util.Optional;

/**
 * Emits a calibrated forecast instead of a position — the signal-generation counterpart of {@link
 * TradingStrategy}, one further step along the line ADR-0018 already drew between actions and
 * target weights (ADR-0028). A {@code SignalStrategy} is never wired to an order path: nothing in
 * {@code viglide-runtime} resolves one through {@link StrategyRegistry}, and there is no adapter
 * from this interface into {@link PortfolioStrategy} or the Risk Manager, unlike {@link
 * TradingStrategy#evaluate} which {@link PortfolioStrategy#ofSingle} can bridge straight into a
 * tradeable target book.
 *
 * <p>Implementations must be deterministic and side-effect free, exactly like {@link
 * TradingStrategy} (NFR-7): identical {@link MarketContext} in, identical output out.
 */
public interface SignalStrategy {

  /**
   * Evaluates the market context and returns a forecast if the context is sufficient.
   *
   * @return empty if the context cannot be evaluated, or if the strategy has no directional view
   *     for this context — never a present {@link Signal}, since {@link Signal} cannot express "no
   *     view" (see its Javadoc)
   */
  Optional<Signal> evaluate(MarketContext context);

  /** Returns the metadata that identifies this strategy for audit and display purposes. */
  StrategyMetadata metadata();
}
