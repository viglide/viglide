package app.viglide.core.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MarketContext}. */
class MarketContextTest {

  private static final BigDecimal P = BigDecimal.ONE;
  private static final BigDecimal H = new BigDecimal("2");

  private Candle candle(String isoTime) {
    return new Candle(Instant.parse(isoTime), P, H, P, P, P);
  }

  @Test
  void happyPath_singleCandle() {
    MarketContext ctx =
        new MarketContext(
            "BTCUSDT", CandleInterval.ONE_HOUR, List.of(candle("2024-01-01T00:00:00Z")));
    assertThat(ctx.symbol()).isEqualTo("BTCUSDT");
    assertThat(ctx.interval()).isEqualTo(CandleInterval.ONE_HOUR);
    assertThat(ctx.candles()).hasSize(1);
  }

  @Test
  void asOf_returnsLastCandleOpenTime() {
    MarketContext ctx =
        new MarketContext(
            "ETHUSDT",
            CandleInterval.ONE_MINUTE,
            List.of(
                candle("2024-01-01T00:00:00Z"),
                candle("2024-01-01T01:00:00Z"),
                candle("2024-01-01T02:00:00Z")));
    assertThat(ctx.asOf()).isEqualTo(Instant.parse("2024-01-01T02:00:00Z"));
  }

  @Test
  void candleListIsDefensivelyCopied() {
    ArrayList<Candle> mutable = new ArrayList<>(List.of(candle("2024-01-01T00:00:00Z")));
    MarketContext ctx = new MarketContext("BTCUSDT", CandleInterval.ONE_HOUR, mutable);
    mutable.add(candle("2024-01-01T01:00:00Z"));
    assertThat(ctx.candles()).hasSize(1);
  }

  @Test
  void rejectsNullSymbol() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new MarketContext(
                    null, CandleInterval.ONE_HOUR, List.of(candle("2024-01-01T00:00:00Z"))));
  }

  @Test
  void rejectsBlankSymbol() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new MarketContext(
                    "  ", CandleInterval.ONE_HOUR, List.of(candle("2024-01-01T00:00:00Z"))));
  }

  @Test
  void rejectsNullInterval() {
    assertThatNullPointerException()
        .isThrownBy(
            () -> new MarketContext("BTCUSDT", null, List.of(candle("2024-01-01T00:00:00Z"))));
  }

  @Test
  void rejectsNullCandleList() {
    assertThatNullPointerException()
        .isThrownBy(() -> new MarketContext("BTCUSDT", CandleInterval.ONE_HOUR, null));
  }

  @Test
  void rejectsEmptyCandleList() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new MarketContext("BTCUSDT", CandleInterval.ONE_HOUR, List.of()));
  }

  @Test
  void rejectsOutOfOrderCandles() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new MarketContext(
                    "BTCUSDT",
                    CandleInterval.ONE_HOUR,
                    List.of(candle("2024-01-01T01:00:00Z"), candle("2024-01-01T00:00:00Z"))));
  }

  @Test
  void rejectsDuplicateOpenTimes() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new MarketContext(
                    "BTCUSDT",
                    CandleInterval.ONE_HOUR,
                    List.of(candle("2024-01-01T00:00:00Z"), candle("2024-01-01T00:00:00Z"))));
  }

  // ── PLAN-010 Task D1: exchangeFilters ────────────────────────────────────────────────────────

  @Test
  void exchangeFilters_defaultsEmptyOnEveryBackwardCompatibleConstructor() {
    List<Candle> candles = List.of(candle("2024-01-01T00:00:00Z"));
    assertThat(new MarketContext("BTCUSDT", CandleInterval.ONE_HOUR, candles).exchangeFilters())
        .isEmpty();
    assertThat(
            new MarketContext("BTCUSDT", CandleInterval.ONE_HOUR, candles, List.of())
                .exchangeFilters())
        .isEmpty();
    assertThat(
            new MarketContext(
                    "BTCUSDT", CandleInterval.ONE_HOUR, candles, List.of(), Optional.empty())
                .exchangeFilters())
        .isEmpty();
  }

  @Test
  void exchangeFilters_explicitValueRoundTrips() {
    ExchangeFilters filters =
        new ExchangeFilters(new BigDecimal("5"), new BigDecimal("0.001"), new BigDecimal("0.10"));
    MarketContext ctx =
        new MarketContext(
            "BTCUSDT",
            CandleInterval.ONE_HOUR,
            List.of(candle("2024-01-01T00:00:00Z")),
            List.of(),
            Optional.empty(),
            Optional.of(filters));
    assertThat(ctx.exchangeFilters()).contains(filters);
  }

  @Test
  void rejectsNullExchangeFilters() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new MarketContext(
                    "BTCUSDT",
                    CandleInterval.ONE_HOUR,
                    List.of(candle("2024-01-01T00:00:00Z")),
                    List.of(),
                    Optional.empty(),
                    null));
  }
}
