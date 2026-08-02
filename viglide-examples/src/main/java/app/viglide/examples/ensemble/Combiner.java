package app.viglide.examples.ensemble;

import app.viglide.core.domain.TechnicalSignal;
import java.util.List;

/**
 * Strategy pattern for ensemble vote combination (PLAN-005a §B.1). Wraps the static {@link
 * EnsembleCombiner} and {@link SharpeWeightedCombiner} methods as a {@link FunctionalInterface} so
 * that {@link EnsembleStrategy} can be constructed with either combiner without branching.
 *
 * <p>Factory methods:
 *
 * <ul>
 *   <li>{@link #naive(EnsembleParameters)} — confidence-weighted equal vote (ADR-0003).
 *   <li>{@link #sharpeWeighted(SharpeWeightedParameters)} — Sharpe-weighted vote (ADR-0005).
 * </ul>
 */
@FunctionalInterface
public interface Combiner {

  /** Combines a non-empty list of strategy votes into a single consensus signal. */
  TechnicalSignal combine(List<EnsembleCombiner.Vote> votes);

  /** Wraps the naive equal-weight combiner. */
  static Combiner naive(EnsembleParameters p) {
    return votes -> EnsembleCombiner.combine(votes, p);
  }

  /** Wraps the Sharpe-weighted combiner. */
  static Combiner sharpeWeighted(SharpeWeightedParameters p) {
    return votes -> SharpeWeightedCombiner.combine(votes, p);
  }
}
