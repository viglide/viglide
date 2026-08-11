package app.viglide.core.spi.fixtures;

import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.Signal;
import app.viglide.core.spi.SignalProvider;
import app.viglide.core.spi.SignalStrategy;
import app.viglide.core.spi.StrategyMetadata;
import java.util.Map;
import java.util.Optional;

/**
 * Test-only {@link SignalProvider}, registered via {@code META-INF/services} for {@link
 * app.viglide.core.spi.SignalRegistryTest}.
 */
public final class FixtureSignalProvider implements SignalProvider {

  @Override
  public String name() {
    return "fixture-signal";
  }

  @Override
  public StrategyMetadata metadata() {
    return new StrategyMetadata("fixture-signal", "1.0", "Test-only fixture signal strategy.");
  }

  @Override
  public SignalStrategy create(Map<String, String> params) {
    return new SignalStrategy() {
      @Override
      public Optional<Signal> evaluate(MarketContext context) {
        return Optional.empty();
      }

      @Override
      public StrategyMetadata metadata() {
        return FixtureSignalProvider.this.metadata();
      }
    };
  }
}
