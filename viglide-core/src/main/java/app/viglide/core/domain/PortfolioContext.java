package app.viglide.core.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Immutable snapshot of every symbol's market data handed to a {@link
 * app.viglide.core.spi.PortfolioStrategy} for one decision bar (PLAN-015 Task A, ADR-0018) — the
 * cross-sectional counterpart of {@link MarketContext}.
 *
 * <p><strong>Determinism (CLAUDE.md §3/§11, NFR-7):</strong> {@link #bySymbol()} is always backed
 * by a symbol-sorted map, regardless of the iteration order of the map passed to the constructor. A
 * strategy that iterates {@code bySymbol().entrySet()} (e.g. to rank every symbol) therefore sees
 * the same order on every run given the same input — required for {@link
 * app.viglide.core.spi.PortfolioStrategy#evaluate} to return the same {@code List<TargetPosition>}
 * ordering across runs, exactly what this task's determinism test asserts.
 *
 * <p>{@code filters} is a single, book-level {@link ExchangeFilters} value, not a per-symbol map —
 * the same documented simplification {@link app.viglide.core.backtest.PortfolioBacktestHarness}
 * already makes for the same reason (a real exchange's {@code minNotional}/step/tick sizes differ
 * pair to pair; a per-symbol {@code Map<String, ExchangeFilters>} is a future evolution, not
 * introduced here). Empty by default, matching every other optional-filters call site in this
 * codebase.
 */
public record PortfolioContext(
    Instant asOf, Map<String, MarketContext> bySymbol, Optional<ExchangeFilters> filters) {

  /**
   * Validates fields and rewraps {@code bySymbol} into an unmodifiable, symbol-sorted copy — see
   * this class's Javadoc on why sortedness is load-bearing for determinism, not cosmetic.
   */
  public PortfolioContext {
    Objects.requireNonNull(asOf, "asOf");
    Objects.requireNonNull(bySymbol, "bySymbol");
    Objects.requireNonNull(filters, "filters");
    if (bySymbol.isEmpty()) {
      throw new IllegalArgumentException("bySymbol must not be empty");
    }
    bySymbol.forEach(
        (symbol, ctx) -> {
          if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("bySymbol keys must not be blank");
          }
          Objects.requireNonNull(ctx, "bySymbol value for " + symbol);
        });
    // Deliberately NOT Map.copyOf: it collapses to a hash-based internal representation that does
    // not preserve TreeMap's sorted iteration order. Collections.unmodifiableMap keeps a live,
    // read-only view over the sorted copy, which is what this class's determinism guarantee needs.
    bySymbol = Collections.unmodifiableMap(new TreeMap<>(bySymbol));
  }

  /** Convenience constructor defaulting {@link #filters()} to empty. */
  public PortfolioContext(Instant asOf, Map<String, MarketContext> bySymbol) {
    this(asOf, bySymbol, Optional.empty());
  }

  /**
   * Looks up one symbol's {@link MarketContext}, if present in this bar's snapshot. A strategy
   * evaluating a fixed universe should treat an absent symbol the same way {@link
   * app.viglide.core.backtest.PortfolioBacktestHarness} treats a per-symbol data gap: skip that
   * symbol for this bar rather than failing the whole evaluation.
   */
  public Optional<MarketContext> forSymbol(String symbol) {
    return Optional.ofNullable(bySymbol.get(symbol));
  }

  /** Applies {@code fn} to each symbol's context, in the guaranteed sorted symbol order. */
  public <T> Map<String, T> mapSymbols(Function<MarketContext, T> fn) {
    Objects.requireNonNull(fn, "fn");
    Map<String, T> out = new TreeMap<>();
    bySymbol.forEach((symbol, ctx) -> out.put(symbol, fn.apply(ctx)));
    return Collections.unmodifiableMap(out);
  }
}
