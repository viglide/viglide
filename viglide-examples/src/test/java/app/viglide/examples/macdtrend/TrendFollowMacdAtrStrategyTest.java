package app.viglide.examples.macdtrend;

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

/**
 * Scenario tests for {@link TrendFollowMacdAtrStrategy}. Uses small custom parameters so fixtures
 * stay manageable (~30 bars) while still exercising the MACD crossover + ATR-active gate.
 */
class TrendFollowMacdAtrStrategyTest {

  /** Small enough that 26-bar fixtures satisfy both MACD (8+2) and ATR (5+15+1=21) minimums. */
  private static MacdTrendParameters tinyParams() {
    return new MacdTrendParameters(3, 8, 2, 5, 15, 0.8, 0.01);
  }

  private TrendFollowMacdAtrStrategy strategy;

  @BeforeEach
  void setUp() {
    strategy = new TrendFollowMacdAtrStrategy(tinyParams());
  }

  // ── BUY scenario ─────────────────────────────────────────────────────────────────────────────

  @Test
  void bullishCrossoverWithActiveAtr_yieldsBuy() {
    Optional<TechnicalSignal> result = strategy.evaluate(load("macdtrend_buy.csv"));
    assertThat(result).isPresent();
    assertThat(result.get().direction()).isEqualTo(Direction.BUY);
  }

  @Test
  void buyFactorsContainMacdCross() {
    Optional<TechnicalSignal> result = strategy.evaluate(load("macdtrend_buy.csv"));
    assertThat(result.get().factors()).anyMatch(f -> f.code().equals("MACD_CROSS"));
  }

  // ── SELL scenario ────────────────────────────────────────────────────────────────────────────

  @Test
  void bearishCrossoverWithActiveAtr_yieldsSell() {
    Optional<TechnicalSignal> result = strategy.evaluate(load("macdtrend_sell.csv"));
    assertThat(result).isPresent();
    assertThat(result.get().direction()).isEqualTo(Direction.SELL);
  }

  // ── HOLD scenario ────────────────────────────────────────────────────────────────────────────

  @Test
  void noCrossover_yieldsHold() {
    Optional<TechnicalSignal> result = strategy.evaluate(load("macdtrend_hold.csv"));
    assertThat(result).isPresent();
    assertThat(result.get().direction()).isEqualTo(Direction.HOLD);
  }

  // ── Insufficient data ────────────────────────────────────────────────────────────────────────

  @Test
  void tooFewCandles_returnsEmpty() {
    Optional<TechnicalSignal> result = strategy.evaluate(load("macdtrend_insufficient.csv"));
    assertThat(result).isEmpty();
  }

  // ── Determinism guard ────────────────────────────────────────────────────────────────────────

  @Test
  void identicalContext_producesIdenticalSignal() {
    MarketContext ctx = load("macdtrend_buy.csv");
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
    assertThat(m.description()).contains("MACD").contains("ATR");
  }

  // ── Parameter validation ─────────────────────────────────────────────────────────────────────

  @Test
  void textbookDefaults_areValid() {
    new TrendFollowMacdAtrStrategy(MacdTrendParameters.textbookDefaults());
  }

  @Test
  void parameters_rejectFastNotLessThanSlow() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new MacdTrendParameters(26, 12, 9, 14, 60, 1.0, 0.5));
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

  private static MarketContext load(String filename) {
    return new MarketContext("BTCUSDT", CandleInterval.ONE_HOUR, CsvFixtureLoader.load(filename));
  }
}
