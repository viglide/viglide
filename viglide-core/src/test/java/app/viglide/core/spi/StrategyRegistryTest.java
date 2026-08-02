package app.viglide.core.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link StrategyRegistry}. */
class StrategyRegistryTest {

  @Test
  void findsFixtureProviderRegisteredViaServiceLoader() {
    StrategyRegistry registry = StrategyRegistry.load();

    assertThat(registry.find("fixture")).isPresent();
    assertThat(registry.find("fixture").orElseThrow().metadata().name()).isEqualTo("fixture");
  }

  @Test
  void findOnUnknownNameReturnsEmpty_notThrows() {
    StrategyRegistry registry = StrategyRegistry.load();

    assertThat(registry.find("does-not-exist")).isEmpty();
  }

  @Test
  void namesAreSorted_neverServiceLoaderOrder() {
    StrategyRegistry registry = StrategyRegistry.load();

    assertThat(List.copyOf(registry.names())).isSorted();
  }

  @Test
  void createUnknownNameThrowsIllegalArgumentException() {
    StrategyRegistry registry = StrategyRegistry.load();

    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> registry.create("does-not-exist", Map.of()))
        .withMessageContaining("unknown --strategy='does-not-exist'");
  }
}
