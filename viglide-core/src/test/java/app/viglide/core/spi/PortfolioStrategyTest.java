package app.viglide.core.spi;

import static org.assertj.core.api.Assertions.*;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.Factor;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.PortfolioContext;
import app.viglide.core.domain.PositionShape;
import app.viglide.core.domain.TargetPosition;
import app.viglide.core.domain.TechnicalSignal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PortfolioStrategy}, in particular the {@link PortfolioStrategy#ofSingle}
 * adapter.
 */
class PortfolioStrategyTest {

  private static final BigDecimal P = BigDecimal.ONE;
  private static final Instant AS_OF = Instant.parse("2024-01-01T00:00:00Z");

  private static Candle candle(String isoTime) {
    return new Candle(Instant.parse(isoTime), P, P, P, P, P);
  }

  private static MarketContext ctx(String symbol) {
    return new MarketContext(
        symbol, CandleInterval.ONE_HOUR, List.of(candle("2024-01-01T00:00:00Z")));
  }

  private static PortfolioContext portfolioOf(String symbol) {
    return new PortfolioContext(AS_OF, Map.of(symbol, ctx(symbol)));
  }

  /** Minimal fixed-response {@link TradingStrategy} for adapter tests. */
  private record FixedSignalStrategy(Optional<TechnicalSignal> response, StrategyKind kind)
      implements TradingStrategy {
    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext context) {
      return response;
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("fixed", "1.0", "fixed test strategy", kind);
    }
  }

  private static TechnicalSignal signal(Direction direction, double confidence) {
    return new TechnicalSignal(
        "BTCUSDT",
        direction,
        confidence,
        List.of(new Factor("F", "detail", 0.5)),
        "explanation for " + direction,
        AS_OF);
  }

  // ── ofSingle: OHLCV kind ─────────────────────────────────────────────────────────────────────

  @Test
  void ofSingle_ohlcvBuy_mapsToPositiveSpotOnlyWeight() {
    TradingStrategy delegate =
        new FixedSignalStrategy(Optional.of(signal(Direction.BUY, 0.7)), StrategyKind.OHLCV);
    PortfolioStrategy adapter = PortfolioStrategy.ofSingle(delegate, "BTCUSDT");

    List<TargetPosition> result = adapter.evaluate(portfolioOf("BTCUSDT"));

    assertThat(result).hasSize(1);
    TargetPosition tp = result.get(0);
    assertThat(tp.symbol()).isEqualTo("BTCUSDT");
    assertThat(tp.targetWeight()).isEqualByComparingTo("0.7");
    assertThat(tp.shape()).isEqualTo(PositionShape.SPOT_ONLY);
    assertThat(tp.explanation()).contains("BUY");
    assertThat(tp.factors()).hasSize(1);
  }

  @Test
  void ofSingle_ohlcvSell_mapsToNegativeSpotOnlyWeight() {
    TradingStrategy delegate =
        new FixedSignalStrategy(Optional.of(signal(Direction.SELL, 0.6)), StrategyKind.OHLCV);
    PortfolioStrategy adapter = PortfolioStrategy.ofSingle(delegate, "BTCUSDT");

    List<TargetPosition> result = adapter.evaluate(portfolioOf("BTCUSDT"));

    assertThat(result).hasSize(1);
    assertThat(result.get(0).targetWeight()).isEqualByComparingTo("-0.6");
    assertThat(result.get(0).shape()).isEqualTo(PositionShape.SPOT_ONLY);
  }

  @Test
  void ofSingle_ohlcvHold_producesEmptyList() {
    TradingStrategy delegate =
        new FixedSignalStrategy(Optional.of(signal(Direction.HOLD, 0.3)), StrategyKind.OHLCV);
    PortfolioStrategy adapter = PortfolioStrategy.ofSingle(delegate, "BTCUSDT");

    assertThat(adapter.evaluate(portfolioOf("BTCUSDT"))).isEmpty();
  }

  @Test
  void ofSingle_insufficientContext_producesEmptyList() {
    TradingStrategy delegate = new FixedSignalStrategy(Optional.empty(), StrategyKind.OHLCV);
    PortfolioStrategy adapter = PortfolioStrategy.ofSingle(delegate, "BTCUSDT");

    assertThat(adapter.evaluate(portfolioOf("BTCUSDT"))).isEmpty();
  }

  @Test
  void ofSingle_symbolAbsentFromContext_producesEmptyList() {
    TradingStrategy delegate =
        new FixedSignalStrategy(Optional.of(signal(Direction.BUY, 0.7)), StrategyKind.OHLCV);
    PortfolioStrategy adapter = PortfolioStrategy.ofSingle(delegate, "ETHUSDT");

    assertThat(adapter.evaluate(portfolioOf("BTCUSDT"))).isEmpty();
  }

  // ── ofSingle: FUNDING_AWARE kind — CLAUDE.md §11 safety mapping ─────────────────────────────

  @Test
  void ofSingle_fundingAwareBuy_mapsToPositiveDeltaNeutralCarryWeight() {
    TradingStrategy delegate =
        new FixedSignalStrategy(
            Optional.of(signal(Direction.BUY, 0.8)), StrategyKind.FUNDING_AWARE);
    PortfolioStrategy adapter = PortfolioStrategy.ofSingle(delegate, "BTCUSDT");

    List<TargetPosition> result = adapter.evaluate(portfolioOf("BTCUSDT"));

    assertThat(result).hasSize(1);
    assertThat(result.get(0).targetWeight()).isEqualByComparingTo("0.8");
    assertThat(result.get(0).shape()).isEqualTo(PositionShape.DELTA_NEUTRAL_CARRY);
  }

  @Test
  void ofSingle_fundingAwareSell_neverProducesNegativeWeight_targetsFlatInstead() {
    TradingStrategy delegate =
        new FixedSignalStrategy(
            Optional.of(signal(Direction.SELL, 0.9)), StrategyKind.FUNDING_AWARE);
    PortfolioStrategy adapter = PortfolioStrategy.ofSingle(delegate, "BTCUSDT");

    // The critical safety assertion (CLAUDE.md §11 / ADR-0014): SELL means "exit the basis trade,"
    // never "reverse into a naked short" -- so the adapter must never emit a negative
    // DELTA_NEUTRAL_CARRY weight. Omission (implicit flat, per TargetPosition's Javadoc) is
    // correct.
    assertThat(adapter.evaluate(portfolioOf("BTCUSDT"))).isEmpty();
  }

  @Test
  void ofSingle_fundingAwareHold_producesEmptyList() {
    TradingStrategy delegate =
        new FixedSignalStrategy(
            Optional.of(signal(Direction.HOLD, 0.4)), StrategyKind.FUNDING_AWARE);
    PortfolioStrategy adapter = PortfolioStrategy.ofSingle(delegate, "BTCUSDT");

    assertThat(adapter.evaluate(portfolioOf("BTCUSDT"))).isEmpty();
  }

  @Test
  void ofSingle_metadataDelegatesToWrappedStrategy() {
    TradingStrategy delegate = new FixedSignalStrategy(Optional.empty(), StrategyKind.OHLCV);
    PortfolioStrategy adapter = PortfolioStrategy.ofSingle(delegate, "BTCUSDT");

    assertThat(adapter.metadata()).isEqualTo(delegate.metadata());
  }

  @Test
  void ofSingle_rejectsNullArgs() {
    TradingStrategy delegate = new FixedSignalStrategy(Optional.empty(), StrategyKind.OHLCV);
    assertThatNullPointerException().isThrownBy(() -> PortfolioStrategy.ofSingle(null, "BTCUSDT"));
    assertThatNullPointerException().isThrownBy(() -> PortfolioStrategy.ofSingle(delegate, null));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PortfolioStrategy.ofSingle(delegate, " "));
  }

  // ── Determinism (PLAN-015 Task A acceptance: identical ordering across runs, NFR-7) ────────

  /**
   * A trivial multi-symbol strategy that echoes back one target per symbol it sees, in context
   * order.
   */
  private static final class EchoAllSymbolsStrategy implements PortfolioStrategy {
    @Override
    public List<TargetPosition> evaluate(PortfolioContext context) {
      List<TargetPosition> out = new java.util.ArrayList<>();
      context
          .bySymbol()
          .forEach(
              (symbol, ctx) ->
                  out.add(
                      new TargetPosition(
                          symbol,
                          BigDecimal.ZERO,
                          PositionShape.SPOT_ONLY,
                          List.of(),
                          "echo " + symbol)));
      return List.copyOf(out);
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("echo", "1.0", "test-only echo strategy");
    }
  }

  @Test
  void evaluate_ordersTargetsBySymbolRegardlessOfInputMapOrder() {
    PortfolioStrategy strategy = new EchoAllSymbolsStrategy();

    Map<String, MarketContext> forward = new LinkedHashMap<>();
    forward.put("ADAUSDT", ctx("ADAUSDT"));
    forward.put("BTCUSDT", ctx("BTCUSDT"));
    forward.put("SOLUSDT", ctx("SOLUSDT"));

    Map<String, MarketContext> reversed = new LinkedHashMap<>();
    reversed.put("SOLUSDT", ctx("SOLUSDT"));
    reversed.put("BTCUSDT", ctx("BTCUSDT"));
    reversed.put("ADAUSDT", ctx("ADAUSDT"));

    List<TargetPosition> fromForward = strategy.evaluate(new PortfolioContext(AS_OF, forward));
    List<TargetPosition> fromReversed = strategy.evaluate(new PortfolioContext(AS_OF, reversed));

    List<String> expectedOrder = List.of("ADAUSDT", "BTCUSDT", "SOLUSDT");
    assertThat(fromForward.stream().map(TargetPosition::symbol))
        .containsExactlyElementsOf(expectedOrder);
    assertThat(fromReversed.stream().map(TargetPosition::symbol))
        .containsExactlyElementsOf(expectedOrder);
    assertThat(fromForward).isEqualTo(fromReversed);
  }

  @Test
  void evaluate_repeatedCallsProduceIdenticalOutput() {
    PortfolioStrategy strategy = new EchoAllSymbolsStrategy();
    PortfolioContext context =
        new PortfolioContext(AS_OF, Map.of("BTCUSDT", ctx("BTCUSDT"), "ETHUSDT", ctx("ETHUSDT")));

    List<TargetPosition> first = strategy.evaluate(context);
    List<TargetPosition> second = strategy.evaluate(context);

    assertThat(first).isEqualTo(second);
  }
}
