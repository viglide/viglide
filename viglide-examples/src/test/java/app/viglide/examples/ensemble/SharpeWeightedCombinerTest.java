package app.viglide.examples.ensemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.data.Offset.offset;

import app.viglide.core.domain.Direction;
import app.viglide.core.domain.Factor;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.spi.StrategyMetadata;
import app.viglide.core.spi.TradingStrategy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SharpeWeightedCombiner} per PLAN-005a Task A acceptance. */
class SharpeWeightedCombinerTest {

  private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

  // ── Task-A test cases ─────────────────────────────────────────────────────────────────────────

  /**
   * When every strategy has negative Sharpe, all raw weights are zero → fallback to equal-weight
   * (w=1.0 per strategy), which is arithmetically identical to the naive combiner. Direction and
   * confidence match what EnsembleCombiner would produce with the same thresholds.
   */
  @Test
  void allNegativeSharpe_fallsBackToEqualWeight_matchesNaiveDirection() {
    // Naive combiner: bull = 0.8 + 0.8 = 1.6, confidence = 1.6 / 2.5 = 0.64
    var params =
        new SharpeWeightedParameters(
            Map.of("A", -0.5, "B", -1.0),
            1.0,
            0.5,
            2.5 // same denominator as naive defaults so arithmetic matches
            );
    var votes = List.of(vote("A", Direction.BUY, 0.8), vote("B", Direction.BUY, 0.8));

    TechnicalSignal result = SharpeWeightedCombiner.combine(votes, params);

    assertThat(result.direction()).isEqualTo(Direction.BUY);
    assertThat(result.confidence()).isCloseTo(0.64, offset(1e-9));
    // Fallback factors carry the SWE_FALLBACK_ prefix
    assertThat(result.factors()).allSatisfy(f -> assertThat(f.code()).startsWith("SWE_FALLBACK_"));
  }

  /**
   * Positive-Sharpe strategies dominate: two BUYs at 0.8 each, Sharpe 1.0 and 0.5. Normalised
   * weights are 1.0/1.5 and 0.5/1.5. bullStrength = 0.8 · (1/1.5) + 0.8 · (0.5/1.5) = 0.8.
   */
  @Test
  void positiveSharpeStrategiesDominate_weighedByShare() {
    var params = new SharpeWeightedParameters(Map.of("A", 1.0, "B", 0.5), 1.0, 0.5, 1.0);
    var votes = List.of(vote("A", Direction.BUY, 0.8), vote("B", Direction.BUY, 0.8));

    TechnicalSignal result = SharpeWeightedCombiner.combine(votes, params);

    assertThat(result.direction()).isEqualTo(Direction.BUY);
    assertThat(result.confidence()).isCloseTo(0.8, offset(1e-9)); // 0.8 / 1.0
    assertThat(result.factors()).allSatisfy(f -> assertThat(f.code()).startsWith("SWE_"));
    assertThat(result.factors()).noneMatch(f -> f.code().startsWith("SWE_FALLBACK_"));
  }

  /**
   * Negative-Sharpe strategy gets weight zero and does not contribute. A (Sharpe 1.0) votes SELL; B
   * (Sharpe −0.5) votes BUY. Without B, bearStrength = 0.6 · 1.0 = 0.6 ≥ 0.5 → SELL. If B were
   * included (with positive Sharpe 0.5), bearStrength = 0.6/(1.5) ≈ 0.4 → potentially HOLD.
   */
  @Test
  void negativeSharpeStrategy_getsZeroWeight_doesNotContribute() {
    var params = new SharpeWeightedParameters(Map.of("A", 1.0, "B", -0.5), 1.0, 0.5, 1.0);
    var votes =
        List.of(
            vote("A", Direction.SELL, 0.6),
            vote("B", Direction.BUY, 0.8) // B excluded: would dilute if included
            );

    TechnicalSignal result = SharpeWeightedCombiner.combine(votes, params);

    // Only A contributes; bearStrength = 0.6 · (1.0/1.0) = 0.6
    assertThat(result.direction()).isEqualTo(Direction.SELL);
    assertThat(result.confidence()).isCloseTo(0.6, offset(1e-9));
  }

  /**
   * Concentration α=2 amplifies the top performer more than α=1. When A (Sharpe 1.0) votes BUY and
   * B (Sharpe 0.5) votes SELL, α=2 gives A a larger share of the vote than α=1, producing a higher
   * winning-side confidence.
   *
   * <ul>
   *   <li>α=1: wA=1/1.5≈0.667, wB=0.5/1.5≈0.333; bull=0.8·0.667=0.533; bear=0.8·0.333=0.267
   *   <li>α=2: wA=1/1.25=0.8, wB=0.25/1.25=0.2; bull=0.8·0.8=0.64; bear=0.8·0.2=0.16
   * </ul>
   */
  @Test
  void concentrationAlpha2_amplifiesTopPerformer() {
    var paramsAlpha1 = new SharpeWeightedParameters(Map.of("A", 1.0, "B", 0.5), 1.0, 0.5, 1.0);
    var paramsAlpha2 = new SharpeWeightedParameters(Map.of("A", 1.0, "B", 0.5), 2.0, 0.5, 1.0);
    var votes = List.of(vote("A", Direction.BUY, 0.8), vote("B", Direction.SELL, 0.8));

    TechnicalSignal r1 = SharpeWeightedCombiner.combine(votes, paramsAlpha1);
    TechnicalSignal r2 = SharpeWeightedCombiner.combine(votes, paramsAlpha2);

    assertThat(r1.direction()).isEqualTo(Direction.BUY);
    assertThat(r2.direction()).isEqualTo(Direction.BUY);
    assertThat(r1.confidence()).isCloseTo(0.533, offset(0.001));
    assertThat(r2.confidence()).isCloseTo(0.64, offset(1e-9));
    assertThat(r2.confidence()).isGreaterThan(r1.confidence());
  }

  // ── Additional edge-case tests ────────────────────────────────────────────────────────────────

  @Test
  void holdVotesAreIgnored_doNotContributeToStrength() {
    // C votes HOLD; only A and B drive the outcome
    var params = new SharpeWeightedParameters(Map.of("A", 1.0, "B", 1.0, "C", 1.0), 1.0, 0.5, 1.0);
    var votes =
        List.of(
            vote("A", Direction.BUY, 0.7),
            vote("B", Direction.BUY, 0.7),
            vote("C", Direction.HOLD, 0.0));

    TechnicalSignal result = SharpeWeightedCombiner.combine(votes, params);

    // wA=wB=wC=1/3; bull = 0.7*(1/3) + 0.7*(1/3) = 0.467 < 0.5 (minVotedConfidence) → HOLD
    assertThat(result.direction()).isEqualTo(Direction.HOLD);
    assertThat(result.confidence()).isEqualTo(0.0);
  }

  @Test
  void emptyVotes_throws() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> SharpeWeightedCombiner.combine(List.of(), SharpeWeightedParameters.defaults()));
  }

  @Test
  void parameters_rejectNegativeConcentration() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SharpeWeightedParameters(Map.of(), -1.0, 0.5, 1.0));
  }

  @Test
  void parameters_rejectInvertedThresholds() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SharpeWeightedParameters(Map.of(), 1.0, 2.0, 1.0));
  }

  @Test
  void parameters_rejectNonPositiveExpectedMaxConfidenceSum() {
    // F16: expectedMaxConfidenceSum must be explicitly positive, checked directly rather than
    // relying on the transitive implication from the minVotedConfidence check.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SharpeWeightedParameters(Map.of(), 1.0, 0.5, 0.0))
        .withMessageContaining("expectedMaxConfidenceSum must be > 0");
  }

  @Test
  void winningFactorsArePrefixedSweAndCappedAtThree() {
    var params = new SharpeWeightedParameters(Map.of("A", 1.0, "B", 0.5), 1.0, 0.5, 1.0);
    var votes = List.of(vote("A", Direction.BUY, 0.8), vote("B", Direction.BUY, 0.8));

    TechnicalSignal result = SharpeWeightedCombiner.combine(votes, params);

    assertThat(result.factors()).hasSizeLessThanOrEqualTo(3);
    assertThat(result.factors()).allSatisfy(f -> assertThat(f.code()).startsWith("SWE_"));
  }

  @Test
  void unknownStrategy_treatedAsZeroSharpe_excludedAtAlpha1() {
    // "Unknown" strategy not in sharpeByStrategy map → Sharpe defaults to 0.0 → weight = 0^1 = 0
    var params = new SharpeWeightedParameters(Map.of("A", 1.0), 1.0, 0.5, 1.0);
    var votes =
        List.of(
            vote("A", Direction.SELL, 0.7),
            vote("Unknown", Direction.BUY, 0.9) // unknown → weight 0 → excluded
            );

    TechnicalSignal result = SharpeWeightedCombiner.combine(votes, params);

    // Only A (SELL) contributes: bearStrength = 0.7 * 1.0 = 0.7 → SELL
    assertThat(result.direction()).isEqualTo(Direction.SELL);
  }

  @Test
  void alphaZero_treatsAllStrategiesEquallyRegardlessOfSharpe() {
    // At α=0: Math.pow(Math.max(0.0, sharpe), 0.0) = 1.0 for any sharpe value (including
    // negative, because max(0, negative) = 0.0 and Math.pow(0.0, 0.0) = 1.0 in Java).
    // So all strategies get rawWeight = 1. totalRawWeight = 2; normalised wA = wB = 0.5.
    // bull = 0.8 * 0.5 + 0.8 * 0.5 = 0.8; confidence = 0.8 / 1.0 = 0.8.
    var params = new SharpeWeightedParameters(Map.of("A", 1.0, "B", -0.5), 0.0, 0.5, 1.0);
    var votes = List.of(vote("A", Direction.BUY, 0.8), vote("B", Direction.BUY, 0.8));

    TechnicalSignal result = SharpeWeightedCombiner.combine(votes, params);

    assertThat(result.direction()).isEqualTo(Direction.BUY);
    assertThat(result.confidence()).isCloseTo(0.8, offset(1e-9));
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

  private static EnsembleCombiner.Vote vote(String name, Direction direction, double confidence) {
    return new EnsembleCombiner.Vote(
        new StubStrategy(name),
        new TechnicalSignal(
            "BTCUSDT",
            direction,
            confidence,
            List.of(new Factor("F1", "detail 1", 0.5), new Factor("F2", "detail 2", 0.4)),
            "stub " + direction + " from " + name,
            T));
  }

  private record StubStrategy(String name) implements TradingStrategy {
    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext context) {
      return Optional.empty();
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata(name, "0.0.1", "stub");
    }
  }
}
