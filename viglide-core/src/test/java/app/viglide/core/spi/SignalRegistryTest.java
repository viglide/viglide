package app.viglide.core.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SignalRegistry}. */
class SignalRegistryTest {

  @Test
  void findsFixtureProviderRegisteredViaServiceLoader() {
    SignalRegistry registry = SignalRegistry.load();

    assertThat(registry.find("fixture-signal")).isPresent();
    assertThat(registry.find("fixture-signal").orElseThrow().metadata().name())
        .isEqualTo("fixture-signal");
  }

  @Test
  void findOnUnknownNameReturnsEmpty_notThrows() {
    SignalRegistry registry = SignalRegistry.load();

    assertThat(registry.find("does-not-exist")).isEmpty();
  }

  @Test
  void namesAreSorted_neverServiceLoaderOrder() {
    // Two fixtures, deliberately: the META-INF/services file lists "fixture-signal" first and
    // "aa-fixture-signal" second, so a registry preserving ServiceLoader's discovery order would
    // return them reversed. With one provider this assertion could not fail (NFR-7).
    SignalRegistry registry = SignalRegistry.load();

    assertThat(List.copyOf(registry.names()))
        .containsExactly("aa-fixture-signal", "fixture-signal")
        .isSorted();
  }

  @Test
  void createUnknownNameThrowsIllegalArgumentException() {
    SignalRegistry registry = SignalRegistry.load();

    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> registry.create("does-not-exist", Map.of()))
        .withMessageContaining("unknown --signal='does-not-exist'");
  }

  @Test
  void createBuildsStrategyFromRegisteredProvider() {
    SignalRegistry registry = SignalRegistry.load();

    SignalStrategy strategy = registry.create("fixture-signal", Map.of());

    assertThat(strategy.metadata().name()).isEqualTo("fixture-signal");
  }
}
