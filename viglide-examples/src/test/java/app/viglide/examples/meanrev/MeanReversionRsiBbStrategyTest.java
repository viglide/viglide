package app.viglide.examples.meanrev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.examples.emarsi.CsvFixtureLoader;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Scenario, validation, and determinism tests for {@link MeanReversionRsiBbStrategy}. */
class MeanReversionRsiBbStrategyTest {

  private MeanReversionRsiBbStrategy strategy;

  @BeforeEach
  void setUp() {
    strategy = new MeanReversionRsiBbStrategy(MeanRevParameters.textbookDefaults());
  }

  // ── BUY scenario ─────────────────────────────────────────────────────────────────────────────

  @Test
  void lowerBandTouchPlusOversold_yieldsBuySignal() {
    Optional<TechnicalSignal> result = strategy.evaluate(load("meanrev_buy.csv"));
    assertThat(result).isPresent();
    assertThat(result.get().direction()).isEqualTo(Direction.BUY);
  }

  @Test
  void buyConfidence_isAtLeastHalf() {
    Optional<TechnicalSignal> result = strategy.evaluate(load("meanrev_buy.csv"));
    assertThat(result.get().confidence()).isGreaterThanOrEqualTo(0.5);
  }

  @Test
  void buyFactorsContainBbTouch() {
    Optional<TechnicalSignal> result = strategy.evaluate(load("meanrev_buy.csv"));
    assertThat(result.get().factors()).anyMatch(f -> f.code().equals("BB_TOUCH"));
  }

  // ── SELL scenario ────────────────────────────────────────────────────────────────────────────

  @Test
  void upperBandTouchPlusOverbought_yieldsSellSignal() {
    Optional<TechnicalSignal> result = strategy.evaluate(load("meanrev_sell.csv"));
    assertThat(result).isPresent();
    assertThat(result.get().direction()).isEqualTo(Direction.SELL);
  }

  // ── HOLD scenario ────────────────────────────────────────────────────────────────────────────

  @Test
  void priceInsideBand_yieldsHold() {
    Optional<TechnicalSignal> result = strategy.evaluate(load("meanrev_hold.csv"));
    assertThat(result).isPresent();
    assertThat(result.get().direction()).isEqualTo(Direction.HOLD);
  }

  // ── Insufficient data ───────────────────────────────────────────────────────────────────────

  @Test
  void tooFewCandles_returnsEmpty() {
    Optional<TechnicalSignal> result = strategy.evaluate(load("meanrev_insufficient.csv"));
    assertThat(result).isEmpty();
  }

  // ── Determinism guard ────────────────────────────────────────────────────────────────────────

  @Test
  void identicalContext_producesIdenticalSignal() {
    MarketContext ctx = load("meanrev_buy.csv");
    TechnicalSignal a = strategy.evaluate(ctx).orElseThrow();
    TechnicalSignal b = strategy.evaluate(ctx).orElseThrow();
    assertThat(a.direction()).isEqualTo(b.direction());
    assertThat(a.confidence()).isEqualTo(b.confidence());
    assertThat(a.explanation()).isEqualTo(b.explanation());
    assertThat(a.factors()).isEqualTo(b.factors());
  }

  // ── Metadata ─────────────────────────────────────────────────────────────────────────────────

  @Test
  void metadata_isNonEmpty() {
    var m = strategy.metadata();
    assertThat(m.name()).isNotBlank();
    assertThat(m.version()).isNotBlank();
    assertThat(m.description()).isNotBlank();
  }

  // ── Parameter validation ─────────────────────────────────────────────────────────────────────

  @Test
  void textbookDefaults_areValid() {
    new MeanReversionRsiBbStrategy(MeanRevParameters.textbookDefaults());
  }

  @Test
  void parameters_rejectInvertedRsiThresholds() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new MeanRevParameters(14, 20, 2.0, 70.0, 30.0, 0.02));
  }

  @Test
  void parameters_rejectNegativeTolerance() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new MeanRevParameters(14, 20, 2.0, 30.0, 70.0, -0.01));
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

  private static MarketContext load(String filename) {
    return new MarketContext("BTCUSDT", CandleInterval.ONE_HOUR, CsvFixtureLoader.load(filename));
  }
}
