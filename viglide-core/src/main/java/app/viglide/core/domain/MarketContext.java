package app.viglide.core.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of market data handed to a strategy for evaluation (PRD §9.1 SPI input).
 * Candles are guaranteed to be in strictly ascending {@link Candle#openTime()} order.
 *
 * <p>{@code fundingHistory} carries Binance perpetual-future funding-rate events relevant to the
 * window — empty for OHLCV-only strategies, populated for funding-arbitrage strategies. The
 * 3-argument constructor defaults it to an empty list so existing strategy code stays
 * source-compatible.
 *
 * <p>{@code lastAtr} optionally carries an ATR(14) value the caller already computed for this bar
 * (F11, PLAN-007 Task D) — {@link app.viglide.core.risk.RiskManager} prefers it over recomputing
 * from scratch on every gate call. Empty when the caller has none; strategies are unaffected unless
 * they choose to read it.
 *
 * <p>{@code exchangeFilters} optionally carries the trading symbol's exchange filters (PLAN-010
 * Task D1, finding F4) — {@link app.viglide.core.risk.RiskManager} rounds the computed size and
 * stop-loss/take-profit prices against it when present, refusing an entry that rounds below the
 * exchange minimum. Empty when the caller has none (the default for every pre-existing caller —
 * research backtests are unaffected unless they opt in via {@code BacktestConfig}).
 */
public record MarketContext(
    String symbol,
    CandleInterval interval,
    List<Candle> candles,
    List<FundingEvent> fundingHistory,
    Optional<BigDecimal> lastAtr,
    Optional<ExchangeFilters> exchangeFilters) {

  /**
   * Validates and defensively copies the lists so callers cannot mutate them after construction.
   * Requires strictly monotonic {@code openTime} on candles to rule out duplicate or out-of-order
   * bars before they corrupt indicator calculations. {@code fundingHistory} is copied but not
   * validated for ordering — most strategies only inspect the tail; ordering is the data layer's
   * responsibility.
   */
  public MarketContext {
    Objects.requireNonNull(symbol, "symbol");
    Objects.requireNonNull(interval, "interval");
    Objects.requireNonNull(candles, "candles");
    Objects.requireNonNull(fundingHistory, "fundingHistory");
    Objects.requireNonNull(lastAtr, "lastAtr");
    Objects.requireNonNull(exchangeFilters, "exchangeFilters");
    if (symbol.isBlank()) throw new IllegalArgumentException("symbol must not be blank");
    if (candles.isEmpty()) throw new IllegalArgumentException("candles must not be empty");
    candles = List.copyOf(candles);
    fundingHistory = List.copyOf(fundingHistory);
    for (int i = 1; i < candles.size(); i++) {
      if (!candles.get(i).openTime().isAfter(candles.get(i - 1).openTime())) {
        throw new IllegalArgumentException(
            "candles must have strictly increasing openTime at index " + i);
      }
    }
  }

  /**
   * Backward-compatible 5-argument constructor for callers that predate {@link #exchangeFilters()}
   * (PLAN-010 Task D1). Defaults it to empty.
   */
  public MarketContext(
      String symbol,
      CandleInterval interval,
      List<Candle> candles,
      List<FundingEvent> fundingHistory,
      Optional<BigDecimal> lastAtr) {
    this(symbol, interval, candles, fundingHistory, lastAtr, Optional.empty());
  }

  /**
   * Backward-compatible 4-argument constructor for callers that don't have a precomputed ATR.
   * Defaults {@link #lastAtr()} to empty.
   */
  public MarketContext(
      String symbol,
      CandleInterval interval,
      List<Candle> candles,
      List<FundingEvent> fundingHistory) {
    this(symbol, interval, candles, fundingHistory, Optional.empty());
  }

  /**
   * Backward-compatible 3-argument constructor for OHLCV-only strategies. Defaults {@link
   * #fundingHistory()} to an empty list.
   */
  public MarketContext(String symbol, CandleInterval interval, List<Candle> candles) {
    this(symbol, interval, candles, List.of());
  }

  /**
   * As-of timestamp of this context, derived from the last candle's open time. No wall-clock access
   * is needed — time is inherited from the data (ADR-0001 §3).
   */
  public Instant asOf() {
    return candles.getLast().openTime();
  }
}
