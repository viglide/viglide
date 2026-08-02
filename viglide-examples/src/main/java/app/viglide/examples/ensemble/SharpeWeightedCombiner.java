package app.viglide.examples.ensemble;

import app.viglide.core.domain.Direction;
import app.viglide.core.domain.Factor;
import app.viglide.core.domain.TechnicalSignal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Sharpe-weighted ensemble combiner (PLAN-005a, ADR-0005). Assigns each strategy a vote weight
 * proportional to {@code max(0, sharpe)^α}, then computes weighted bull/bear sums. Strategies with
 * negative recent Sharpe receive weight zero and are silently excluded from the vote.
 *
 * <p>Weight formula (per strategy {@code s}):
 *
 * <ol>
 *   <li>{@code rawWeight_s = max(0, sharpe_s)^α}
 *   <li>Normalise: {@code w_s = rawWeight_s / Σ rawWeight_s}
 *   <li>If all raw weights are zero (every strategy has non-positive Sharpe), fall back to equal
 *       weight {@code w_s = 1.0} — identical to {@link EnsembleCombiner}'s arithmetic except
 *       factors are prefixed with {@code SWE_FALLBACK_} for traceability.
 * </ol>
 *
 * <p>Then for non-HOLD votes: {@code bullStrength = Σ confidence_v · w_v} over BUY votes; mirror
 * for SELL. Direction and confidence follow the same threshold logic as the naive combiner (using
 * {@link SharpeWeightedParameters#minVotedConfidence} and {@link
 * SharpeWeightedParameters#expectedMaxConfidenceSum}).
 */
public final class SharpeWeightedCombiner {

  private SharpeWeightedCombiner() {}

  /**
   * @throws IllegalArgumentException if {@code votes} is empty
   */
  public static TechnicalSignal combine(
      List<EnsembleCombiner.Vote> votes, SharpeWeightedParameters cfg) {
    Objects.requireNonNull(votes, "votes");
    Objects.requireNonNull(cfg, "cfg");
    if (votes.isEmpty()) {
      throw new IllegalArgumentException("ensemble requires at least one vote");
    }

    String symbol = votes.get(0).signal().symbol();
    var asOf = votes.get(0).signal().asOf();

    // Compute raw weights for every vote.
    double totalRawWeight = 0.0;
    double[] rawWeights = new double[votes.size()];
    for (int i = 0; i < votes.size(); i++) {
      String name = votes.get(i).strategy().metadata().name();
      double sharpe = cfg.sharpeByStrategy().getOrDefault(name, 0.0);
      double raw = Math.pow(Math.max(0.0, sharpe), cfg.concentration());
      rawWeights[i] = raw;
      totalRawWeight += raw;
    }

    boolean fallback = totalRawWeight == 0.0;
    String factorPrefix = fallback ? "SWE_FALLBACK_" : "SWE_";

    double bull = 0.0;
    double bear = 0.0;
    List<Factor> bullFactors = new ArrayList<>();
    List<Factor> bearFactors = new ArrayList<>();
    List<String> bullStrats = new ArrayList<>();
    List<String> bearStrats = new ArrayList<>();
    List<String> holdStrats = new ArrayList<>();

    for (int i = 0; i < votes.size(); i++) {
      EnsembleCombiner.Vote v = votes.get(i);
      String name = v.strategy().metadata().name();
      double w = fallback ? 1.0 : rawWeights[i] / totalRawWeight;
      String prefix = factorPrefix + name + "_";

      switch (v.signal().direction()) {
        case BUY -> {
          bull += v.signal().confidence() * w;
          bullStrats.add(name);
          addPrefixed(bullFactors, prefix, v.signal().factors());
        }
        case SELL -> {
          bear += v.signal().confidence() * w;
          bearStrats.add(name);
          addPrefixed(bearFactors, prefix, v.signal().factors());
        }
        case HOLD -> holdStrats.add(name);
      }
    }

    Direction direction;
    double winningSum;
    List<Factor> winningFactors;
    List<String> winningStrats;
    if (bull > bear && bull >= cfg.minVotedConfidence()) {
      direction = Direction.BUY;
      winningSum = bull;
      winningFactors = bullFactors;
      winningStrats = bullStrats;
    } else if (bear > bull && bear >= cfg.minVotedConfidence()) {
      direction = Direction.SELL;
      winningSum = bear;
      winningFactors = bearFactors;
      winningStrats = bearStrats;
    } else {
      direction = Direction.HOLD;
      winningSum = 0.0;
      winningFactors = List.of();
      winningStrats = List.of();
    }

    double confidence =
        direction == Direction.HOLD
            ? 0.0
            : Math.clamp(winningSum / cfg.expectedMaxConfidenceSum(), 0.0, 1.0);

    String holdLabel = fallback ? "SWE_FALLBACK_HOLD" : "SWE_HOLD";
    List<Factor> outFactors =
        winningFactors.isEmpty()
            ? List.of(new Factor(holdLabel, "no consensus across " + votes.size() + " votes", 0.0))
            : winningFactors.subList(0, Math.min(3, winningFactors.size()));

    String explanation = buildExplanation(direction, symbol, winningStrats, votes.size(), fallback);
    return new TechnicalSignal(symbol, direction, confidence, outFactors, explanation, asOf);
  }

  private static void addPrefixed(List<Factor> sink, String prefix, List<Factor> src) {
    for (Factor f : src) {
      sink.add(new Factor(prefix + f.code(), f.detail(), f.contribution()));
    }
  }

  private static String buildExplanation(
      Direction direction,
      String symbol,
      List<String> winningStrats,
      int totalVotes,
      boolean fallback) {
    String tag = fallback ? "SWE-Fallback" : "SWE";
    if (direction == Direction.HOLD) {
      return tag + " HOLD " + symbol + ": no consensus across " + totalVotes + " votes.";
    }
    return tag
        + " "
        + direction
        + " "
        + symbol
        + ": "
        + winningStrats.size()
        + " of "
        + totalVotes
        + " strategies agree ("
        + String.join(", ", winningStrats)
        + ").";
  }
}
