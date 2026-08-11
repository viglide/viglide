package app.viglide.core.spi;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * {@link ServiceLoader}-backed lookup of every {@link SignalProvider} on the classpath, by name —
 * the {@link SignalStrategy} counterpart of {@link StrategyRegistry}, same discovery and sorting
 * rules (NFR-7: {@link ServiceLoader}'s discovery order is unspecified and must never leak into a
 * CLI error message or a deterministic report).
 */
public final class SignalRegistry {

  private final SortedMap<String, SignalProvider> providersByName;

  private SignalRegistry(SortedMap<String, SignalProvider> providersByName) {
    this.providersByName = providersByName;
  }

  /** Loads every {@link SignalProvider} visible to the given class loader. */
  public static SignalRegistry load(ClassLoader classLoader) {
    SortedMap<String, SignalProvider> byName = new TreeMap<>(Comparator.naturalOrder());
    for (SignalProvider provider : ServiceLoader.load(SignalProvider.class, classLoader)) {
      byName.put(provider.name(), provider);
    }
    return new SignalRegistry(byName);
  }

  /** Loads using the current thread's context class loader. */
  public static SignalRegistry load() {
    return load(Thread.currentThread().getContextClassLoader());
  }

  /** Returns the provider registered under {@code name}, if any. */
  public Optional<SignalProvider> find(String name) {
    return Optional.ofNullable(providersByName.get(name));
  }

  /** Every registered signal name, sorted — never {@link ServiceLoader}'s discovery order. */
  public java.util.Set<String> names() {
    return java.util.Collections.unmodifiableSet(providersByName.keySet());
  }

  /**
   * Convenience for the common "build or fail with a helpful message" call site.
   *
   * @throws IllegalArgumentException if {@code name} is not registered
   */
  public SignalStrategy create(String name, Map<String, String> params) {
    SignalProvider provider =
        find(name)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "unknown --signal='" + name + "'; known: " + names()));
    return provider.create(params);
  }
}
