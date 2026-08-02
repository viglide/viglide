package app.viglide.core.spi;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * {@link ServiceLoader}-backed lookup of every {@link StrategyProvider} on the classpath, by name.
 * The public runtime and the research CLIs depend on this class, never on a concrete strategy
 * class, so which providers are actually present is purely a classpath-composition question
 * (ADR-0025 §5.1) — the private repo's deployable puts the private providers on the classpath, the
 * public build does not.
 */
public final class StrategyRegistry {

  private final SortedMap<String, StrategyProvider> providersByName;

  private StrategyRegistry(SortedMap<String, StrategyProvider> providersByName) {
    this.providersByName = providersByName;
  }

  /** Loads every {@link StrategyProvider} visible to the given class loader. */
  public static StrategyRegistry load(ClassLoader classLoader) {
    SortedMap<String, StrategyProvider> byName = new TreeMap<>(Comparator.naturalOrder());
    for (StrategyProvider provider : ServiceLoader.load(StrategyProvider.class, classLoader)) {
      byName.put(provider.name(), provider);
    }
    return new StrategyRegistry(byName);
  }

  /** Loads using the current thread's context class loader. */
  public static StrategyRegistry load() {
    return load(Thread.currentThread().getContextClassLoader());
  }

  /** Returns the provider registered under {@code name}, if any. */
  public Optional<StrategyProvider> find(String name) {
    return Optional.ofNullable(providersByName.get(name));
  }

  /**
   * Every registered strategy name, sorted — never {@link ServiceLoader}'s discovery order, which
   * is unspecified and would make CLI error messages ("known: …") nondeterministic (NFR-7).
   */
  public java.util.Set<String> names() {
    return java.util.Collections.unmodifiableSet(providersByName.keySet());
  }

  /**
   * Convenience for the common "build or fail with a helpful message" call site.
   *
   * @throws IllegalArgumentException if {@code name} is not registered
   */
  public app.viglide.core.spi.TradingStrategy create(String name, Map<String, String> params) {
    StrategyProvider provider =
        find(name)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "unknown --strategy='" + name + "'; known: " + names()));
    return provider.create(params);
  }
}
