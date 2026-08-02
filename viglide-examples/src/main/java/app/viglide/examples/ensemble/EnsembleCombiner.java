package app.viglide.examples.ensemble;

import app.viglide.core.domain.Direction;
import app.viglide.core.domain.Factor;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.spi.TradingStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure function that combines a non-empty list of strategy votes into a single consensus signal
 * (ADR-0003, PLAN-003 §C.2). Algorithm:
 *
 * <ol>
 *   <li>Drop HOLD votes — they neither support nor oppose action.
 *   <li>Sum the BUY confidences as {@code bullStrength}; same for SELL as {@code bearStrength}.
 *   <li>If {@code bullStrength > bearStrength} and {@code bullStrength >= minVotedConfidence}, emit
 *       BUY. Mirror for SELL. Otherwise HOLD.
 *   <li>Confidence = (winning sum) / {@code expectedMaxConfidenceSum}, clamped to [0, 1].
 *   <li>Factors: take the winning side's top-3 underlying factors, prefixed with {@code
 *       ENS_<strategy>_<originalCode>} so the audit trail is unambiguous.
 * </ol>
 *
 * <p>This is the <strong>naive</strong> precursor to the PRD §8 Layer-2 Decision Router. It does
 * not gate on portfolio sizing, route to AI debate, or weight strategies by historical Sharpe —
 * those land in a later plan.
 */
public final class EnsembleCombiner {

  /** One vote from a single underlying strategy. */
  public record Vote(TradingStrategy strategy, TechnicalSignal signal) {
    public Vote {
      Objects.requireNonNull(strategy, "strategy");
      Objects.requireNonNull(signal, "signal");
    }
  }

  private EnsembleCombiner() {}

  /**
   * @throws IllegalArgumentException if {@code votes} is empty
   */
  public static TechnicalSignal combine(List<Vote> votes, EnsembleParameters cfg) {
    Objects.requireNonNull(votes, "votes");
    Objects.requireNonNull(cfg, "cfg");
    if (votes.isEmpty()) {
      throw new IllegalArgumentException("ensemble requires at least one vote");
    }

    String symbol = votes.get(0).signal().symbol();
    var asOf = votes.get(0).signal().asOf();

    double bull = 0.0;
    double bear = 0.0;
    List<Factor> bullFactors = new ArrayList<>();
    List<Factor> bearFactors = new ArrayList<>();
    List<String> bullStrats = new ArrayList<>();
    List<String> bearStrats = new ArrayList<>();
    List<String> holdStrats = new ArrayList<>();

    for (Vote v : votes) {
      String name = v.strategy().metadata().name();
      switch (v.signal().direction()) {
        case BUY -> {
          bull += v.signal().confidence();
          bullStrats.add(name);
          addPrefixed(bullFactors, name, v.signal().factors());
        }
        case SELL -> {
          bear += v.signal().confidence();
          bearStrats.add(name);
          addPrefixed(bearFactors, name, v.signal().factors());
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

    List<Factor> outFactors =
        winningFactors.isEmpty()
            ? List.of(new Factor("ENS_HOLD", "no consensus across " + votes.size() + " votes", 0.0))
            : winningFactors.subList(0, Math.min(3, winningFactors.size()));

    String explanation = buildExplanation(direction, symbol, winningStrats, votes.size());

    return new TechnicalSignal(symbol, direction, confidence, outFactors, explanation, asOf);
  }

  private static void addPrefixed(List<Factor> sink, String strategyName, List<Factor> src) {
    for (Factor f : src) {
      sink.add(new Factor("ENS_" + strategyName + "_" + f.code(), f.detail(), f.contribution()));
    }
  }

  private static String buildExplanation(
      Direction direction, String symbol, List<String> winningStrats, int totalVotes) {
    if (direction == Direction.HOLD) {
      return "Ensemble HOLD " + symbol + ": no consensus across " + totalVotes + " votes.";
    }
    return "Ensemble "
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
