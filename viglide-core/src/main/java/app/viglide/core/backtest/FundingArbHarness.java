package app.viglide.core.backtest;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.indicator.IndicatorMath;
import app.viglide.core.risk.CircuitBreaker;
import app.viglide.core.risk.ExecutionDecision;
import app.viglide.core.risk.PortfolioState;
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

/**
 * Backtest harness for funding-rate arbitrage (delta-neutral basis carry). Walks candles and
 * funding events in chronological order. On BUY: opens a basis position (long spot + short perp)
 * paying 2× normal taker+slippage. While open, every funding event credits the position. On SELL:
 * closes the position paying 2× normal taker+slippage. The position has <strong>no price
 * exposure</strong> — mark-to-market is simply {@code cash + positionNotional} (no high/low SL/TP
 * checks).
 *
 * <p>This is the simplified Phase-1 model documented in PLAN-004 §1.2: we treat the basis trade as
 * a pure funding-income stream and ignore spot/perp basis variance. Phase-2 will add explicit
 * spot-leg modelling.
 *
 * <p>The harness emits the same {@link BacktestResult} shape as {@link BacktestHarness}, so all
 * downstream CLI writers (result.json, trades.csv, equity.csv) work unchanged.
 */
public final class FundingArbHarness {

  private FundingArbHarness() {}

  /**
   * Runs the strategy over the (candle, funding) timeline and returns the aggregated result.
   *
   * @param strategy the funding-aware strategy under test (must consume {@code
   *     MarketContext.fundingHistory()})
   * @param candles full candle history in ascending {@code openTime} order
   * @param fundingEvents funding-rate events in ascending {@code time} order
   * @param symbol instrument symbol, e.g. {@code "BTCUSDT"}
   * @param interval candle interval the candle list was sampled at
   * @param cfg backtest knobs; {@code stopLossPct} and {@code takeProfitPct} are ignored because
   *     the basis trade has no price exposure
   */
  /**
   * Runs the strategy over (candle, funding) timeline with optional Risk Manager gating.
   *
   * @param rm optional Risk Manager; {@link Optional#empty()} for the legacy ungated path
   */
  public static BacktestResult run(
      TradingStrategy strategy,
      List<Candle> candles,
      List<FundingEvent> fundingEvents,
      String symbol,
      CandleInterval interval,
      BacktestConfig cfg,
      Optional<RiskManagerPort> rm) {

    BigDecimal adverseFactor = cfg.fees().totalAdverseFactor(IndicatorMath.MC);
    BigDecimal dualLegCost = adverseFactor.multiply(BigDecimal.valueOf(2), IndicatorMath.MC);
    BigDecimal cash = cfg.startingCash();

    BigDecimal positionNotional = null;
    Instant positionEntryTime = null;
    List<Trade> trades = new ArrayList<>();
    List<EquityPoint> equityCurve = new ArrayList<>();
    Deque<Candle> window = new ArrayDeque<>(cfg.warmupBars());
    Deque<FundingEvent> fundingWindow = new ArrayDeque<>();

    int fundingIdx = 0;
    Direction pending = null;
    long evaluations = 0;
    long signals = 0;
    long rmRefusals = 0;
    List<Refusal> refusals = new ArrayList<>();

    BigDecimal peakEquity = cfg.startingCash();
    boolean cbTripped = false;
    BigDecimal maxDrawdownPct =
        rm.map(r -> r.riskParameters().maxPortfolioDrawdownPct()).orElse(new BigDecimal("0.15"));

    for (Candle candle : candles) {
      // 1. Apply any funding events that fall strictly before this candle's openTime.
      while (fundingIdx < fundingEvents.size()
          && !fundingEvents.get(fundingIdx).time().isAfter(candle.openTime())) {
        FundingEvent fe = fundingEvents.get(fundingIdx++);
        fundingWindow.addLast(fe);
        if (positionNotional != null) {
          BigDecimal payment = positionNotional.multiply(fe.rate(), IndicatorMath.MC);
          cash = cash.add(payment);
        }
      }

      // 2. Fill the pending order (placed by previous candle's strategy call) at this candle's
      // open.
      if (!cbTripped && pending == Direction.BUY && positionNotional == null) {
        BigDecimal notional = cash.multiply(cfg.maxPositionPct(), IndicatorMath.MC);
        BigDecimal entryFee = notional.multiply(dualLegCost, IndicatorMath.MC);
        cash = cash.subtract(entryFee);
        positionNotional = notional;
        positionEntryTime = candle.openTime();
      } else if (pending == Direction.SELL && positionNotional != null) {
        BigDecimal exitFee = positionNotional.multiply(dualLegCost, IndicatorMath.MC);
        cash = cash.subtract(exitFee);
        trades.add(
            new Trade(
                positionEntryTime,
                candle.openTime(),
                Direction.BUY, // basis trade has no directional side; record BUY by convention
                BigDecimal.ONE, // synthetic "price" — basis trade has no entry price
                BigDecimal.ONE,
                positionNotional,
                BigDecimal.ZERO, // realised exit-side cost; funding already credited via accruals
                ExitReason.SIGNAL));
        positionNotional = null;
        positionEntryTime = null;
      }
      pending = null;

      // 3. Slide the window.
      if (window.size() >= cfg.warmupBars()) {
        window.pollFirst();
      }
      window.addLast(candle);

      // Mark-to-market: cash + notional (delta-neutral, no price exposure).
      BigDecimal mark = positionNotional == null ? cash : cash.add(positionNotional);
      equityCurve.add(new EquityPoint(candle.openTime(), mark));

      // Circuit-breaker check (RM path only).
      if (rm.isPresent() && !cbTripped) {
        if (mark.compareTo(peakEquity) > 0) peakEquity = mark;
        if (CircuitBreaker.shouldTrip(mark, peakEquity, maxDrawdownPct)) {
          cbTripped = true;
          // Close open position immediately.
          if (positionNotional != null) {
            BigDecimal exitFee = positionNotional.multiply(dualLegCost, IndicatorMath.MC);
            cash = cash.subtract(exitFee);
            trades.add(
                new Trade(
                    positionEntryTime,
                    candle.openTime(),
                    Direction.BUY,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    positionNotional,
                    BigDecimal.ZERO,
                    ExitReason.STOP_LOSS));
            positionNotional = null;
            positionEntryTime = null;
          }
        }
      }

      // 4. Evaluate strategy (skip if CB tripped).
      if (window.size() == cfg.warmupBars() && !cbTripped) {
        evaluations++;
        MarketContext ctx =
            new MarketContext(
                symbol,
                interval,
                new ArrayList<>(window),
                new ArrayList<>(fundingWindow),
                Optional.empty(),
                cfg.exchangeFilters());
        Optional<TechnicalSignal> sig = strategy.evaluate(ctx);
        if (sig.isPresent()) {
          signals++;
          Direction d = sig.get().direction();
          if (rm.isPresent() && d != Direction.HOLD) {
            BigDecimal equity = positionNotional == null ? cash : cash.add(positionNotional);
            PortfolioState state =
                new PortfolioState(
                    cash,
                    equity,
                    peakEquity.max(equity),
                    positionNotional == null ? Map.of() : Map.of(symbol, positionNotional),
                    cbTripped,
                    Optional.empty());
            ExecutionDecision decision = rm.get().gate(sig.get(), state, ctx);
            if (decision instanceof ExecutionDecision.Execute exec) {
              if (exec.side() == Direction.BUY && positionNotional == null) pending = Direction.BUY;
              if (exec.side() == Direction.SELL && positionNotional != null)
                pending = Direction.SELL;
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
            if (d == Direction.BUY && positionNotional == null) pending = Direction.BUY;
            if (d == Direction.SELL && positionNotional != null) pending = Direction.SELL;
          }
        }
      }
    }

    // End-of-data sweep: drain any remaining funding events up to the last candle's close.
    Candle lastCandle = candles.isEmpty() ? null : candles.get(candles.size() - 1);
    while (fundingIdx < fundingEvents.size()) {
      FundingEvent fe = fundingEvents.get(fundingIdx++);
      if (positionNotional != null) {
        BigDecimal payment = positionNotional.multiply(fe.rate(), IndicatorMath.MC);
        cash = cash.add(payment);
      }
    }

    // Close any open position.
    if (positionNotional != null && lastCandle != null) {
      BigDecimal exitFee = positionNotional.multiply(dualLegCost, IndicatorMath.MC);
      cash = cash.subtract(exitFee);
      trades.add(
          new Trade(
              positionEntryTime,
              lastCandle.openTime(),
              Direction.BUY,
              BigDecimal.ONE,
              BigDecimal.ONE,
              positionNotional,
              BigDecimal.ZERO,
              ExitReason.END_OF_DATA));
      positionNotional = null;
    }

    BigDecimal endingEquity = cash;
    BigDecimal totalReturn =
        cfg.startingCash().signum() == 0
            ? BigDecimal.ZERO
            : endingEquity
                .subtract(cfg.startingCash())
                .divide(cfg.startingCash(), IndicatorMath.MC);
    double sharpe = Metrics.annualisedSharpe(equityCurve);
    BigDecimal maxDd = Metrics.maxDrawdown(equityCurve);
    double winRate = equityBasedWinRate(trades, equityCurve);

    Map<String, Object> diag = new HashMap<>();
    diag.put("evaluations", evaluations);
    diag.put("signals", signals);
    diag.put("bars", (long) equityCurve.size());
    diag.put("fundingEvents", (long) fundingEvents.size());
    diag.put("model", "funding-arb-simplified");
    diag.put("harness", "funding-arb"); // F10: identifies which harness produced this result.
    if (rm.isPresent()) {
      diag.put("rmRefusals", rmRefusals);
      diag.put("rmGated", true);
    }

    return new BacktestResult(
        cfg.startingCash(),
        endingEquity,
        totalReturn,
        sharpe,
        maxDd,
        winRate,
        trades.size(),
        trades,
        equityCurve,
        diag,
        refusals);
  }

  /** Backward-compatible overload that runs without a Risk Manager (legacy ungated path). */
  public static BacktestResult run(
      TradingStrategy strategy,
      List<Candle> candles,
      List<FundingEvent> fundingEvents,
      String symbol,
      CandleInterval interval,
      BacktestConfig cfg) {
    return run(strategy, candles, fundingEvents, symbol, interval, cfg, Optional.empty());
  }

  /**
   * Win-rate variant for the funding-arb harness. The per-trade {@code pnl} field is always zero
   * because funding accrues continuously to cash; the meaningful "win" is whether equity rose
   * across the trade's lifetime. We look up equity at {@code entryTime} and {@code exitTime} from
   * the curve and count trades where the second is higher.
   */
  private static double equityBasedWinRate(List<Trade> trades, List<EquityPoint> equityCurve) {
    if (trades.isEmpty() || equityCurve.isEmpty()) return 0.0;
    int wins = 0;
    for (Trade t : trades) {
      BigDecimal entryEq = equityAt(equityCurve, t.entryTime());
      BigDecimal exitEq = equityAt(equityCurve, t.exitTime());
      if (entryEq != null && exitEq != null && exitEq.compareTo(entryEq) > 0) wins++;
    }
    return (double) wins / trades.size();
  }

  /** Binary search by equity curve timestamp; returns the closest point at or before {@code t}. */
  private static BigDecimal equityAt(List<EquityPoint> curve, Instant t) {
    int lo = 0;
    int hi = curve.size() - 1;
    BigDecimal best = null;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      EquityPoint p = curve.get(mid);
      if (p.at().isAfter(t)) {
        hi = mid - 1;
      } else {
        best = p.equity();
        lo = mid + 1;
      }
    }
    return best;
  }
}
