package app.viglide.core.spi;

import java.util.Comparator;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * {@link ServiceLoader}-backed lookup of every {@link ParameterSpaceProvider}, by strategy name.
 */
public final class ParameterSpaceRegistry {

  private final SortedMap<String, ParameterSpaceProvider> providersByName;

  private ParameterSpaceRegistry(SortedMap<String, ParameterSpaceProvider> providersByName) {
    this.providersByName = providersByName;
  }

  public static ParameterSpaceRegistry load(ClassLoader classLoader) {
    SortedMap<String, ParameterSpaceProvider> byName = new TreeMap<>(Comparator.naturalOrder());
    for (ParameterSpaceProvider provider :
        ServiceLoader.load(ParameterSpaceProvider.class, classLoader)) {
      byName.put(provider.name(), provider);
    }
    return new ParameterSpaceRegistry(byName);
  }

  public static ParameterSpaceRegistry load() {
    return load(Thread.currentThread().getContextClassLoader());
  }

  public Optional<ParameterSpaceProvider> find(String name) {
    return Optional.ofNullable(providersByName.get(name));
  }

  /** Every registered name, sorted — never {@link ServiceLoader}'s unspecified discovery order. */
  public java.util.Set<String> names() {
    return java.util.Collections.unmodifiableSet(providersByName.keySet());
  }
}
