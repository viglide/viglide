package app.viglide.examples.ensemble;

import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.spi.StrategyKind;
import app.viglide.core.spi.StrategyMetadata;
import app.viglide.core.spi.StrategyStatus;
import app.viglide.core.spi.TradingStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link TradingStrategy} adapter that asks each underlying strategy in turn for a signal and
 * combines them through an injected {@link Combiner}. Returns {@link Optional#empty()} only if
 * every underlying strategy returns empty (e.g. the window is too short for any of them).
 *
 * <p>The combiner is supplied at construction time via {@link Combiner#naive} (ADR-0003) or {@link
 * Combiner#sharpeWeighted} (ADR-0005).
 */
public final class EnsembleStrategy implements TradingStrategy {

  private final List<TradingStrategy> underlying;
  private final Combiner combiner;

  public EnsembleStrategy(List<TradingStrategy> underlying, Combiner combiner) {
    Objects.requireNonNull(underlying, "underlying");
    Objects.requireNonNull(combiner, "combiner");
    if (underlying.isEmpty()) {
      throw new IllegalArgumentException("ensemble requires at least one underlying strategy");
    }
    this.underlying = List.copyOf(underlying);
    this.combiner = combiner;
  }

  @Override
  public Optional<TechnicalSignal> evaluate(MarketContext context) {
    List<EnsembleCombiner.Vote> votes = new ArrayList<>(underlying.size());
    for (TradingStrategy s : underlying) {
      Optional<TechnicalSignal> sig = s.evaluate(context);
      sig.ifPresent(t -> votes.add(new EnsembleCombiner.Vote(s, t)));
    }
    if (votes.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(combiner.combine(votes));
  }

  @Override
  public StrategyMetadata metadata() {
    StringBuilder desc = new StringBuilder("Ensemble vote across ");
    for (int i = 0; i < underlying.size(); i++) {
      if (i > 0) desc.append(", ");
      desc.append(underlying.get(i).metadata().name());
    }
    // PLAN-015 Task G: both combiners share this class (Combiner.naive / Combiner.sharpeWeighted
    // choose behaviour at construction time, ADR-0003/ADR-0005) -- both lost to best-single OOS
    // (ARCHITECTURE.md R7), so both are marked BENCHMARK_ONLY here in one place.
    return new StrategyMetadata(
        "Ensemble", "0.2.0", desc.toString(), StrategyKind.OHLCV, StrategyStatus.BENCHMARK_ONLY);
  }
}
