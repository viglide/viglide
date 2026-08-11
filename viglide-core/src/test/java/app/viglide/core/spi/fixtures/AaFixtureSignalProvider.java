package app.viglide.core.spi.fixtures;

import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.Signal;
import app.viglide.core.spi.SignalProvider;
import app.viglide.core.spi.SignalStrategy;
import app.viglide.core.spi.StrategyMetadata;
import java.util.Map;
import java.util.Optional;

/**
 * A second test-only {@link SignalProvider}, existing only so {@code
 * SignalRegistryTest.namesAreSorted_neverServiceLoaderOrder} can fail. With one provider registered
 * that assertion was tautological — a single-element list is sorted by definition — and the
 * property it claims to protect (NFR-7: {@link java.util.ServiceLoader}'s discovery order must
 * never leak into a report) was untested.
 *
 * <p>Its name sorts <em>before</em> {@link FixtureSignalProvider}'s while its {@code
 * META-INF/services} entry is listed <em>after</em> it, so a registry that preserved discovery
 * order would return them the wrong way round.
 */
public final class AaFixtureSignalProvider implements SignalProvider {

  @Override
  public String name() {
    return "aa-fixture-signal";
  }

  @Override
  public StrategyMetadata metadata() {
    return new StrategyMetadata(
        "aa-fixture-signal", "1.0", "Test-only fixture signal strategy, sorts first.");
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
        return AaFixtureSignalProvider.this.metadata();
      }
    };
  }
}
