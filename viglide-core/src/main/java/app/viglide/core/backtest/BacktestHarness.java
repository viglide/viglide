package app.viglide.core.backtest;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.indicator.AtrCalculator;
import app.viglide.core.indicator.IndicatorMath;
import app.viglide.core.risk.CircuitBreaker;
import app.viglide.core.risk.ExecutionDecision;
import app.viglide.core.risk.PortfolioState;
import app.viglide.core.risk.RiskManager;
import app.viglide.core.risk.RiskManagerPort;
import app.viglide.core.spi.TradingStrategy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Replays a candle stream through a {@link TradingStrategy} and a {@link VirtualExchange},
 * producing a deterministic {@link BacktestResult}.
 *
 * <p>Window discipline: a sliding window of size {@link BacktestConfig#warmupBars()} candles is
 * maintained; the strategy is invoked only once the window is full, so the first {@code
 * warmupBars-1} candles are warmup-only and do not generate trades.
 *
 * <p>Per-bar event order (no look-ahead):
 *
 * <ol>
 *   <li>New candle arrives.
 *   <li>{@code VirtualExchange.onCandle(candle)} — fills the pending order from the previous bar at
 *       {@code candle.open()}, then checks SL/TP against the bar's range, then marks-to-market.
 *   <li>Append candle to the rolling window (evict head if full).
 *   <li>If window is full, invoke {@code strategy.evaluate(...)} on the window's snapshot.
 *   <li>If the strategy returns a non-HOLD signal, queue it as a pending order for the next bar.
 * </ol>
 *
 * <p>This timing means a strategy signal generated at bar i is filled at bar (i+1)'s open — the
 * standard "next-bar fill" assumption that avoids look-ahead bias.
 *
 * <p>F10 (PLAN-007 Task D): this is the OHLCV harness — the only one that handles price exposure,
 * stop-loss, and take-profit. It also accepts an optional funding-event stream so ensembles that
 * mix a funding-aware member with OHLCV members still see {@code MarketContext.fundingHistory()};
 * it never applies funding-rate cash effects itself (that is {@link FundingArbHarness}'s job).
 */
public final class BacktestHarness {

  private BacktestHarness() {}

  /**
   * Runs the strategy over the candle stream, optionally gating orders through a Risk Manager and
   * optionally exposing a funding-event stream via {@code MarketContext.fundingHistory()}.
   *
   * <p>The stream is consumed in iteration order; for large datasets prefer {@link
   * app.viglide.core.data.CsvKlineReader#stream} which is line-streaming.
   *
   * @param strategy the strategy under test (deterministic, side-effect free per NFR-7)
   * @param candles candle stream, oldest first
   * @param symbol instrument symbol, e.g. {@code "BTCUSDT"}
   * @param interval the candle interval the stream was sampled at
   * @param cfg backtest knobs (cash, fees, warmup, SL/TP)
   * @param fundingEvents funding-rate events, ascending {@code time} order; empty for OHLCV-only
   *     runs. Exposed to the strategy/RM via {@code MarketContext} but never affects cash (F10).
   * @param rm optional Risk Manager gate; {@link Optional#empty()} for the legacy ungated path
   * @param subBarCandles PLAN-009 Task B2: optional finer-grained candle series (e.g. 1m bars)
   *     covering the same period, ascending by {@code openTime}, used only to resolve SL/TP
   *     ordering inside each decision bar more accurately ({@link VirtualExchange#onCandle(Candle,
   *     List)}). Empty (the default via the overloads below) preserves the original
   *     worst-case-pessimistic behaviour exactly.
   */
  public static BacktestResult run(
      TradingStrategy strategy,
      Stream<Candle> candles,
      String symbol,
      CandleInterval interval,
      BacktestConfig cfg,
      List<FundingEvent> fundingEvents,
      Optional<RiskManagerPort> rm,
      List<Candle> subBarCandles) {

    VirtualExchange exchange = new VirtualExchange(symbol, cfg, interval);
    Deque<Candle> window = new ArrayDeque<>(cfg.warmupBars());
    Deque<FundingEvent> fundingWindow = new ArrayDeque<>();
    int fundingIdx = 0;
    int subBarIdx = 0;
    long evaluated = 0;
    long signals = 0;
    long rmRefusals = 0;
    List<Refusal> refusals = new ArrayList<>();
    Candle lastCandle = null;

    // Circuit-breaker state tracked by the harness (per Task C).
    BigDecimal peakEquity = cfg.startingCash();
    boolean cbTripped = false;
    BigDecimal maxDrawdownPct =
        rm.map(r -> r.riskParameters().maxPortfolioDrawdownPct()).orElse(new BigDecimal("0.15"));

    var iter = candles.iterator();
    while (iter.hasNext()) {
      Candle candle = iter.next();

      // 1. Process the previous bar's pending order + SL/TP + mark-to-market. Sub-bars (if any)
      // covering [candle.openTime(), nextOpenTime) are sliced with a monotonic cursor — sub-bars
      // orphaned by a gap in the decision series (skipped ahead of the cursor) are dropped rather
      // than misattributed to a later bar.
      List<Candle> subBarSlice = List.of();
      if (!subBarCandles.isEmpty()) {
        Instant windowEnd = candle.openTime().plus(interval.duration());
        while (subBarIdx < subBarCandles.size()
            && subBarCandles.get(subBarIdx).openTime().isBefore(candle.openTime())) {
          subBarIdx++;
        }
        int sliceStart = subBarIdx;
        while (subBarIdx < subBarCandles.size()
            && subBarCandles.get(subBarIdx).openTime().isBefore(windowEnd)) {
          subBarIdx++;
        }
        subBarSlice = subBarCandles.subList(sliceStart, subBarIdx);
      }
      exchange.onCandle(candle, subBarSlice);

      // Update peak equity and check the circuit breaker on every bar (RM path only).
      if (rm.isPresent() && !cbTripped) {
        BigDecimal equity = exchange.markToMarketNow(candle.close());
        if (equity.compareTo(peakEquity) > 0) peakEquity = equity;
        if (CircuitBreaker.shouldTrip(equity, peakEquity, maxDrawdownPct)) {
          cbTripped = true;
          exchange.closeAll(candle);
        }
      }

      // 1b. Drain funding events up to this candle's openTime into the visible window (F10).
      while (fundingIdx < fundingEvents.size()
          && !fundingEvents.get(fundingIdx).time().isAfter(candle.openTime())) {
        fundingWindow.addLast(fundingEvents.get(fundingIdx++));
      }

      // 2. Slide the window.
      if (window.size() >= cfg.warmupBars()) {
        window.pollFirst();
      }
      window.addLast(candle);

      // 3. Evaluate once the window is full and CB is not tripped.
      if (window.size() == cfg.warmupBars() && !cbTripped) {
        evaluated++;
        List<Candle> windowSnapshot = new ArrayList<>(window);
        // F11: compute ATR once per bar (only needed for the RM path) instead of letting the RM
        // recompute it from scratch on every gate call.
        Optional<BigDecimal> lastAtr =
            rm.isPresent() ? computeAtrIfEnoughCandles(windowSnapshot) : Optional.empty();
        MarketContext ctx =
            new MarketContext(
                symbol,
                interval,
                windowSnapshot,
                new ArrayList<>(fundingWindow),
                lastAtr,
                cfg.exchangeFilters());
        Optional<TechnicalSignal> sig = strategy.evaluate(ctx);
        if (sig.isPresent()) {
          signals++;
          if (rm.isPresent() && sig.get().direction() != Direction.HOLD) {
            BigDecimal equity = exchange.markToMarketNow(candle.close());
            PortfolioState state =
                buildPortfolioState(
                    exchange, symbol, candle.close(), peakEquity.max(equity), cbTripped);
            ExecutionDecision decision = rm.get().gate(sig.get(), state, ctx);
            if (decision instanceof ExecutionDecision.Execute exec) {
              exchange.onExecuteDecision(exec);
            } else {
              rmRefusals++;
              ExecutionDecision.Refuse refuse = (ExecutionDecision.Refuse) decision;
              refusals.add(
                  new Refusal(
                      refuse.asOf(),
                      symbol,
                      sig.get().direction(),
                      refuse.reason(),
                      refuse.explanation()));
            }
          } else {
            exchange.onSignal(sig.get());
          }
        }
      }
      lastCandle = candle;
    }

    if (lastCandle != null) {
      exchange.closeAll(lastCandle);
    }

    return buildResult(cfg, exchange, evaluated, signals, rmRefusals, rm.isPresent(), refusals);
  }

  /** Overload with a funding-event stream but no sub-bars (RM-gated or not, per {@code rm}). */
  public static BacktestResult run(
      TradingStrategy strategy,
      Stream<Candle> candles,
      String symbol,
      CandleInterval interval,
      BacktestConfig cfg,
      List<FundingEvent> fundingEvents,
      Optional<RiskManagerPort> rm) {
    return run(strategy, candles, symbol, interval, cfg, fundingEvents, rm, List.of());
  }

  /** Overload without a funding-event stream (OHLCV-only, RM-gated). */
  public static BacktestResult run(
      TradingStrategy strategy,
      Stream<Candle> candles,
      String symbol,
      CandleInterval interval,
      BacktestConfig cfg,
      Optional<RiskManagerPort> rm) {
    return run(strategy, candles, symbol, interval, cfg, List.of(), rm, List.of());
  }

  /** Backward-compatible overload that runs without a Risk Manager (legacy ungated path). */
  public static BacktestResult run(
      TradingStrategy strategy,
      Stream<Candle> candles,
      String symbol,
      CandleInterval interval,
      BacktestConfig cfg) {
    return run(strategy, candles, symbol, interval, cfg, List.of(), Optional.empty(), List.of());
  }

  /**
   * ATR(14) needs {@code RiskManager.ATR_PERIOD + 1} candles; {@code warmupBars} can be configured
   * smaller than that (e.g. in tests), so this returns empty rather than throwing — the RM's own
   * internal fallback then applies its "insufficient candles" refusal as before.
   */
  private static Optional<BigDecimal> computeAtrIfEnoughCandles(List<Candle> window) {
    if (window.size() < RiskManager.ATR_PERIOD + 1) {
      return Optional.empty();
    }
    return Optional.of(AtrCalculator.calculate(window, RiskManager.ATR_PERIOD).values().getLast());
  }

  private static PortfolioState buildPortfolioState(
      VirtualExchange exchange,
      String symbol,
      BigDecimal markPrice,
      BigDecimal peakEquity,
      boolean cbTripped) {
    BigDecimal equity = exchange.markToMarketNow(markPrice);
    // F3/F12: notional = size × markPrice, computed directly (matching FundingArbHarness's
    // producer pattern) rather than derived as equity − cash, which only happens to coincide with
    // notional under today's single-position accounting and would silently drift if that ever
    // changes.
    Map<String, BigDecimal> positions =
        exchange.hasOpenPosition()
            ? Map.of(symbol, exchange.positionSize().multiply(markPrice, IndicatorMath.MC))
            : Map.of();
    return new PortfolioState(
        exchange.cash(), equity, peakEquity, positions, cbTripped, Optional.empty());
  }

  private static BacktestResult buildResult(
      BacktestConfig cfg,
      VirtualExchange exchange,
      long evaluated,
      long signals,
      long rmRefusals,
      boolean rmGated,
      List<Refusal> refusals) {
    List<Trade> trades = exchange.trades();
    List<EquityPoint> curve = exchange.equityCurve();
    BigDecimal start = cfg.startingCash();
    BigDecimal end = curve.isEmpty() ? start : curve.get(curve.size() - 1).equity();
    BigDecimal totalReturn =
        start.signum() == 0 ? BigDecimal.ZERO : end.subtract(start).divide(start, IndicatorMath.MC);
    double sharpe = Metrics.annualisedSharpe(curve);
    BigDecimal maxDd = Metrics.maxDrawdown(curve);
    double winRate = Metrics.winRate(trades);

    Map<String, Object> diag = new HashMap<>();
    diag.put("evaluations", evaluated);
    diag.put("signals", signals);
    diag.put("totalFeesPaid", exchange.totalFeesPaid()); // PLAN-009 Task C
    diag.put("bars", (long) curve.size());
    diag.put("harness", "ohlcv"); // F10: identifies which harness produced this result.
    if (rmGated) {
      diag.put("rmRefusals", rmRefusals);
      diag.put("rmGated", true);
    }

    return new BacktestResult(
        start,
        end,
        totalReturn,
        sharpe,
        maxDd,
        winRate,
        trades.size(),
        trades,
        curve,
        diag,
        refusals);
  }
}
