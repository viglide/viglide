package app.viglide.core.spi.fixtures;

import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.spi.StrategyMetadata;
import app.viglide.core.spi.StrategyProvider;
import app.viglide.core.spi.TradingStrategy;
import java.util.Map;
import java.util.Optional;

/**
 * Test-only {@link StrategyProvider}, registered via {@code META-INF/services} for {@link
 * app.viglide.core.spi.StrategyRegistryTest}.
 */
public final class FixtureStrategyProvider implements StrategyProvider {

  @Override
  public String name() {
    return "fixture";
  }

  @Override
  public StrategyMetadata metadata() {
    return new StrategyMetadata("fixture", "1.0", "Test-only fixture strategy.");
  }

  @Override
  public TradingStrategy create(Map<String, String> params) {
    return new TradingStrategy() {
      @Override
      public Optional<TechnicalSignal> evaluate(MarketContext context) {
        return Optional.empty();
      }

      @Override
      public StrategyMetadata metadata() {
        return FixtureStrategyProvider.this.metadata();
      }
    };
  }
}
