package app.viglide.core.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PortfolioContext}. */
class PortfolioContextTest {

  private static final BigDecimal P = BigDecimal.ONE;

  private static Candle candle(String isoTime) {
    return new Candle(Instant.parse(isoTime), P, P, P, P, P);
  }

  private static MarketContext ctx(String symbol) {
    return new MarketContext(
        symbol, CandleInterval.ONE_HOUR, List.of(candle("2024-01-01T00:00:00Z")));
  }

  @Test
  void bySymbol_isAlwaysSortedRegardlessOfInputOrder() {
    Map<String, MarketContext> insertionOrder = new LinkedHashMap<>();
    insertionOrder.put("SOLUSDT", ctx("SOLUSDT"));
    insertionOrder.put("ADAUSDT", ctx("ADAUSDT"));
    insertionOrder.put("BTCUSDT", ctx("BTCUSDT"));

    PortfolioContext pc =
        new PortfolioContext(Instant.parse("2024-01-01T00:00:00Z"), insertionOrder);

    assertThat(pc.bySymbol().keySet()).containsExactly("ADAUSDT", "BTCUSDT", "SOLUSDT");
  }

  @Test
  void forSymbol_presentAndAbsent() {
    PortfolioContext pc =
        new PortfolioContext(
            Instant.parse("2024-01-01T00:00:00Z"), Map.of("BTCUSDT", ctx("BTCUSDT")));
    assertThat(pc.forSymbol("BTCUSDT")).isPresent();
    assertThat(pc.forSymbol("ETHUSDT")).isEmpty();
  }

  @Test
  void filters_defaultsToEmpty() {
    PortfolioContext pc =
        new PortfolioContext(
            Instant.parse("2024-01-01T00:00:00Z"), Map.of("BTCUSDT", ctx("BTCUSDT")));
    assertThat(pc.filters()).isEmpty();
  }

  @Test
  void rejectsEmptyBySymbol() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new PortfolioContext(Instant.parse("2024-01-01T00:00:00Z"), Map.of()));
  }

  @Test
  void bySymbolIsDefensivelyCopied() {
    Map<String, MarketContext> mutable = new LinkedHashMap<>();
    mutable.put("BTCUSDT", ctx("BTCUSDT"));
    PortfolioContext pc = new PortfolioContext(Instant.parse("2024-01-01T00:00:00Z"), mutable);
    mutable.put("ETHUSDT", ctx("ETHUSDT"));
    assertThat(pc.bySymbol()).hasSize(1);
  }

  @Test
  void mapSymbols_preservesSortedOrder() {
    Map<String, MarketContext> insertionOrder = new LinkedHashMap<>();
    insertionOrder.put("SOLUSDT", ctx("SOLUSDT"));
    insertionOrder.put("ADAUSDT", ctx("ADAUSDT"));
    PortfolioContext pc =
        new PortfolioContext(Instant.parse("2024-01-01T00:00:00Z"), insertionOrder);

    Map<String, String> symbols = pc.mapSymbols(MarketContext::symbol);
    assertThat(symbols.keySet()).containsExactly("ADAUSDT", "SOLUSDT");
  }

  @Test
  void rejectsNullFields() {
    assertThatNullPointerException()
        .isThrownBy(() -> new PortfolioContext(null, Map.of("BTCUSDT", ctx("BTCUSDT"))));
    assertThatNullPointerException()
        .isThrownBy(() -> new PortfolioContext(Instant.parse("2024-01-01T00:00:00Z"), null));
  }
}
