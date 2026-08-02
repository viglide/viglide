package app.viglide.examples.ensemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import app.viglide.core.domain.Direction;
import app.viglide.core.domain.Factor;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.spi.StrategyMetadata;
import app.viglide.core.spi.TradingStrategy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link EnsembleCombiner} per PLAN-003 §C.2 acceptance. */
class EnsembleCombinerTest {

  private static final Instant T = Instant.parse("2024-01-01T00:00:00Z");

  @Test
  void twoBuysAndOneHold_yieldsBuyAtExpectedConfidence() {
    // Two BUYs at 0.8 each + one HOLD ⇒ bullStrength = 1.6 / 2.5 = 0.64
    var votes =
        List.of(
            vote("A", Direction.BUY, 0.8),
            vote("B", Direction.BUY, 0.8),
            vote("C", Direction.HOLD, 0.0));
    TechnicalSignal s = EnsembleCombiner.combine(votes, EnsembleParameters.defaults());
    assertThat(s.direction()).isEqualTo(Direction.BUY);
    assertThat(s.confidence()).isEqualTo(0.64, org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  void unanimousSell_yieldsHighConfidenceSell() {
    var votes =
        List.of(
            vote("A", Direction.SELL, 0.9),
            vote("B", Direction.SELL, 0.85),
            vote("C", Direction.SELL, 0.8));
    TechnicalSignal s = EnsembleCombiner.combine(votes, EnsembleParameters.defaults());
    assertThat(s.direction()).isEqualTo(Direction.SELL);
    // 0.9 + 0.85 + 0.8 = 2.55 → clamped to 1.0
    assertThat(s.confidence()).isEqualTo(1.0);
  }

  @Test
  void evenlySplitBuyVsSell_yieldsHold() {
    var votes = List.of(vote("A", Direction.BUY, 0.7), vote("B", Direction.SELL, 0.7));
    TechnicalSignal s = EnsembleCombiner.combine(votes, EnsembleParameters.defaults());
    assertThat(s.direction()).isEqualTo(Direction.HOLD);
    assertThat(s.confidence()).isEqualTo(0.0);
  }

  @Test
  void belowMinVotedConfidence_yieldsHold() {
    // BUY of 0.4 < min 0.5 ⇒ HOLD.
    var votes = List.of(vote("A", Direction.BUY, 0.4));
    TechnicalSignal s = EnsembleCombiner.combine(votes, EnsembleParameters.defaults());
    assertThat(s.direction()).isEqualTo(Direction.HOLD);
  }

  @Test
  void winningFactorsArePrefixedAndCapped() {
    var votes = List.of(vote("A", Direction.BUY, 0.8), vote("B", Direction.BUY, 0.8));
    TechnicalSignal s = EnsembleCombiner.combine(votes, EnsembleParameters.defaults());
    // Two strategies × 2 factors each = 4 total; combiner keeps top 3.
    assertThat(s.factors()).hasSizeLessThanOrEqualTo(3);
    assertThat(s.factors()).allSatisfy(f -> assertThat(f.code()).startsWith("ENS_"));
  }

  @Test
  void emptyVotes_throws() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> EnsembleCombiner.combine(List.of(), EnsembleParameters.defaults()));
  }

  @Test
  void parameters_rejectInvertedThresholds() {
    assertThatIllegalArgumentException().isThrownBy(() -> new EnsembleParameters(2.0, 1.0));
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
