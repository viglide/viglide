package app.viglide.core.spi;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.backtest.FeeModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ParameterSpaceRegistry}. */
class ParameterSpaceRegistryTest {

  @Test
  void findsFixtureProviderRegisteredViaServiceLoader() {
    ParameterSpaceRegistry registry = ParameterSpaceRegistry.load();

    assertThat(registry.find("fixture")).isPresent();
    assertThat(registry.find("fixture").orElseThrow().grid(FeeModel.taker())).isNotEmpty();
  }

  @Test
  void findOnUnknownNameReturnsEmpty_notThrows() {
    ParameterSpaceRegistry registry = ParameterSpaceRegistry.load();

    assertThat(registry.find("does-not-exist")).isEmpty();
  }

  @Test
  void namesAreSorted_neverServiceLoaderOrder() {
    ParameterSpaceRegistry registry = ParameterSpaceRegistry.load();

    assertThat(List.copyOf(registry.names())).isSorted();
  }
}
