package app.viglide.core.spi;

import java.util.Comparator;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * {@link ServiceLoader}-backed lookup of every {@link PortfolioParameterSpaceProvider}, by strategy
 * name — {@link ParameterSpaceRegistry}'s cross-sectional counterpart (PLAN-022 Task B).
 */
public final class PortfolioParameterSpaceRegistry {

  private final SortedMap<String, PortfolioParameterSpaceProvider> providersByName;

  private PortfolioParameterSpaceRegistry(
      SortedMap<String, PortfolioParameterSpaceProvider> providersByName) {
    this.providersByName = providersByName;
  }

  public static PortfolioParameterSpaceRegistry load(ClassLoader classLoader) {
    SortedMap<String, PortfolioParameterSpaceProvider> byName =
        new TreeMap<>(Comparator.naturalOrder());
    for (PortfolioParameterSpaceProvider provider :
        ServiceLoader.load(PortfolioParameterSpaceProvider.class, classLoader)) {
      byName.put(provider.name(), provider);
    }
    return new PortfolioParameterSpaceRegistry(byName);
  }

  public static PortfolioParameterSpaceRegistry load() {
    return load(Thread.currentThread().getContextClassLoader());
  }

  public Optional<PortfolioParameterSpaceProvider> find(String name) {
    return Optional.ofNullable(providersByName.get(name));
  }

  /** Every registered name, sorted — never {@link ServiceLoader}'s unspecified discovery order. */
  public java.util.Set<String> names() {
    return java.util.Collections.unmodifiableSet(providersByName.keySet());
  }
}
