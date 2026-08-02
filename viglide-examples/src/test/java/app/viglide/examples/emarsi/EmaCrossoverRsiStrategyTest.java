package app.viglide.examples.emarsi;

import static org.assertj.core.api.Assertions.*;

import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Scenario, property, and determinism tests for {@link EmaCrossoverRsiStrategy}. */
class EmaCrossoverRsiStrategyTest {

  private EmaCrossoverRsiStrategy strategy;

  @BeforeEach
  void setUp() {
    strategy = new EmaCrossoverRsiStrategy(StrategyParameters.textbookDefaults());
  }

  // ── Scenario: bullish crossover ──────────────────────────────────────────────────────────────

  @Test
  void bullishCross_yieldsBuySignal() {
    MarketContext ctx = loadContext("bullish_cross.csv");
    Optional<TechnicalSignal> result = strategy.evaluate(ctx);

    assertThat(result).isPresent();
    assertThat(result.get().direction()).isEqualTo(Direction.BUY);
  }

  @Test
  void bullishCross_confidenceAtLeastHalf() {
    Optional<TechnicalSignal> result = strategy.evaluate(loadContext("bullish_cross.csv"));
    assertThat(result.get().confidence()).isGreaterThanOrEqualTo(0.5);
  }

  @Test
  void bullishCross_factorsContainEmaCrossover() {
    Optional<TechnicalSignal> result = strategy.evaluate(loadContext("bullish_cross.csv"));
    assertThat(result.get().factors()).anyMatch(f -> f.code().equals("EMA_CROSSOVER"));
  }

  @Test
  void bullishCross_explanationMentionsBuy() {
    Optional<TechnicalSignal> result = strategy.evaluate(loadContext("bullish_cross.csv"));
    assertThat(result.get().explanation()).contains("BUY");
  }

  // ── Scenario: bearish crossover ──────────────────────────────────────────────────────────────

  @Test
  void bearishCross_yieldsSellSignal() {
    Optional<TechnicalSignal> result = strategy.evaluate(loadContext("bearish_cross.csv"));
    assertThat(result).isPresent();
    assertThat(result.get().direction()).isEqualTo(Direction.SELL);
  }

  @Test
  void bearishCross_confidenceAtLeastHalf() {
    Optional<TechnicalSignal> result = strategy.evaluate(loadContext("bearish_cross.csv"));
    assertThat(result.get().confidence()).isGreaterThanOrEqualTo(0.5);
  }

  @Test
  void bearishCross_explanationMentionsSell() {
    Optional<TechnicalSignal> result = strategy.evaluate(loadContext("bearish_cross.csv"));
    assertThat(result.get().explanation()).contains("SELL");
  }

  // ── Scenario: neutral / no crossover ────────────────────────────────────────────────────────

  @Test
  void flatPrices_yieldsHoldSignal() {
    Optional<TechnicalSignal> result = strategy.evaluate(loadContext("neutral_flat.csv"));
    assertThat(result).isPresent();
    assertThat(result.get().direction()).isEqualTo(Direction.HOLD);
  }

  // ── Scenario: insufficient candles ──────────────────────────────────────────────────────────

  @Test
  void insufficientCandles_returnsEmpty() {
    Optional<TechnicalSignal> result = strategy.evaluate(loadContext("insufficient.csv"));
    assertThat(result).isEmpty();
  }

  // ── Golden value (NFR-7) ─────────────────────────────────────────────────────────────────────

  /**
   * Pins the exact confidence the textbook EMA(9/21)+RSI(14) strategy produces for the canonical
   * bullish fixture. Any drift here is the canary for a non-determinism leak — see PLAN-001 §3.5.
   */
  @Test
  void bullishCross_confidenceMatchesPinnedGolden() {
    Optional<TechnicalSignal> result = strategy.evaluate(loadContext("bullish_cross.csv"));
    // Pinned 2026-06-14 (PLAN-002 §D.5). Any drift here is the canary for a non-determinism leak.
    assertThat(result.get().confidence()).isEqualTo(0.510025262133934);
  }

  // ── Determinism guard (NFR-7) ────────────────────────────────────────────────────────────────

  @Test
  void identicalContext_producesIdenticalSignal() {
    MarketContext ctx = loadContext("bullish_cross.csv");
    Optional<TechnicalSignal> first = strategy.evaluate(ctx);
    Optional<TechnicalSignal> second = strategy.evaluate(ctx);

    assertThat(first).isPresent();
    assertThat(second).isPresent();
    TechnicalSignal s1 = first.get();
    TechnicalSignal s2 = second.get();

    assertThat(s1.direction()).isEqualTo(s2.direction());
    assertThat(s1.confidence()).isEqualTo(s2.confidence());
    assertThat(s1.explanation()).isEqualTo(s2.explanation());
    assertThat(s1.factors()).isEqualTo(s2.factors());
    assertThat(s1.asOf()).isEqualTo(s2.asOf());
  }

  // ── Metadata ─────────────────────────────────────────────────────────────────────────────────

  @Test
  void metadata_isNonEmpty() {
    assertThat(strategy.metadata().name()).isNotBlank();
    assertThat(strategy.metadata().version()).isNotBlank();
    assertThat(strategy.metadata().description()).isNotBlank();
  }

  // ── StrategyParameters validation ────────────────────────────────────────────────────────────

  @Test
  void textbookDefaults_areValid() {
    assertThatCode(StrategyParameters::textbookDefaults).doesNotThrowAnyException();
  }

  @Test
  void parameters_rejectFastGtSlow() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StrategyParameters(21, 9, 14, 70, 30, 0.01));
  }

  @Test
  void parameters_rejectInvalidRsiThresholds() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StrategyParameters(9, 21, 14, 30, 70, 0.01)); // overbought < oversold
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

  private static MarketContext loadContext(String filename) {
    return new MarketContext("BTCUSDT", CandleInterval.ONE_HOUR, CsvFixtureLoader.load(filename));
  }
}
