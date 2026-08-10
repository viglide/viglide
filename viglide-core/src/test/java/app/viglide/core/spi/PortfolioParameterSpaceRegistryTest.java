package app.viglide.core.spi;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.backtest.FeeModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PortfolioParameterSpaceRegistry}. */
class PortfolioParameterSpaceRegistryTest {

  @Test
  void findsFixtureProviderRegisteredViaServiceLoader() {
    PortfolioParameterSpaceRegistry registry = PortfolioParameterSpaceRegistry.load();

    assertThat(registry.find("fixture-portfolio")).isPresent();
    assertThat(registry.find("fixture-portfolio").orElseThrow().grid(FeeModel.taker()))
        .isNotEmpty();
  }

  @Test
  void findOnUnknownNameReturnsEmpty_notThrows() {
    PortfolioParameterSpaceRegistry registry = PortfolioParameterSpaceRegistry.load();

    assertThat(registry.find("does-not-exist")).isEmpty();
  }

  @Test
  void namesAreSorted_neverServiceLoaderOrder() {
    PortfolioParameterSpaceRegistry registry = PortfolioParameterSpaceRegistry.load();

    assertThat(List.copyOf(registry.names())).isSorted();
  }
}
