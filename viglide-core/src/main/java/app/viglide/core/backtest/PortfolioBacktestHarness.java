package app.viglide.core.backtest;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.Factor;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.PortfolioContext;
import app.viglide.core.domain.PositionShape;
import app.viglide.core.domain.TargetPosition;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.indicator.IndicatorMath;
import app.viglide.core.risk.CircuitBreaker;
import app.viglide.core.risk.ExecutionDecision;
import app.viglide.core.risk.PortfolioState;
import app.viglide.core.risk.RiskManagerPort;
import app.viglide.core.spi.PortfolioStrategy;
import app.viglide.core.spi.TradingStrategy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Multi-symbol backtest harness (PLAN-008 Task H): CLAUDE.md §7's hard limits (2× leverage, 15%
 * drawdown circuit breaker) are portfolio-scoped, and no single-symbol harness can exercise them —
 * a per-pair backtest structurally cannot see that <em>another</em> pair already committed 1.5× of
 * equity. This harness shares one cash balance and one {@link RiskManagerPort} gate across every
 * symbol, so aggregate exposure is what the RM actually checks.
 *
 * <p><strong>Modelling decision (read before trusting portfolio-level P&amp;L):</strong> each
 * symbol gets full price exposure — a BUY opens a real long position at that bar's close, marked to
 * market every bar, exactly like {@link BacktestHarness}'s single-symbol model — and, when funding
 * events are supplied for a symbol, funding also accrues to cash while a position is open (rate ×
 * size × current close, credited on the short-perp-receives-positive-funding convention {@link
 * FundingArbHarness}/{@link FundingArbHarnessV2} use). This is deliberately <em>not</em> the
 * delta-neutral two-leg model — this harness takes no spot-dataset input, so there is no offsetting
 * spot leg to hedge price risk. Running {@code fundingarb} through this harness therefore measures
 * "the funding-rate signal, fully price-exposed, portfolio-risk-gated" — a real and useful number
 * (it is what actually happens if you act on the signal without spot-hedging), but a <strong>more
 * conservative one</strong> than {@link FundingArbHarnessV2}'s hedged result, since it carries
 * directional risk the real delta-neutral trade would not. ADR-0009 covers the two-leg model's own
 * assumptions; this Javadoc is the equivalent honesty note for the portfolio harness.
 *
 * <p><strong>Bar iteration:</strong> the ascending union of every symbol's {@code openTime} values.
 * At each timestamp, a symbol without a candle at that exact time is skipped for that bar (counted
 * in diagnostics as {@code barsSkipped.<SYMBOL>}) — this tolerates per-symbol data gaps without
 * corrupting the other symbols' series.
 *
 * <p><strong>Circuit breaker (portfolio-scoped, CLAUDE.md §7):</strong> checked once per bar-time
 * after every symbol has been processed, using aggregate portfolio equity. Once tripped: (a) every
 * symbol's open position is liquidated at <em>that symbol's next bar's open</em> (not immediately —
 * a symbol with a data gap right at the trip is liquidated at its next actual bar, taker fees
 * applied, {@link ExitReason#STOP_LOSS}); (b) the Risk Manager itself refuses every subsequent
 * signal with {@code CIRCUIT_BREAKER_TRIPPED} — the harness does not special-case this, it simply
 * keeps evaluating strategies and gating as normal, and {@code RiskManagerPort}'s own {@code
 * PortfolioState.circuitBreakerTripped()} check does the rest, so every post-trip signal still
 * produces an auditable {@link Refusal}.
 */
public final class PortfolioBacktestHarness {

  private PortfolioBacktestHarness() {}

  /**
   * @param candlesBySymbol 1h candles per symbol, each list ascending by {@code openTime}
   * @param strategiesBySymbol the strategy to evaluate for each symbol (may differ per symbol)
   * @param fundingBySymbol funding events per symbol, ascending by {@code time}; a symbol absent
   *     from this map (or mapped to an empty list) simply never accrues funding
   * @param rm mandatory — CLAUDE.md §11 forbids an ungated order path, portfolio mode included
   */
  public static BacktestResult run(
      Map<String, List<Candle>> candlesBySymbol,
      Map<String, TradingStrategy> strategiesBySymbol,
      Map<String, List<FundingEvent>> fundingBySymbol,
      CandleInterval interval,
      BacktestConfig cfg,
      RiskManagerPort rm) {
    Objects.requireNonNull(candlesBySymbol, "candlesBySymbol");
    Objects.requireNonNull(strategiesBySymbol, "strategiesBySymbol");
    Objects.requireNonNull(fundingBySymbol, "fundingBySymbol");
    Objects.requireNonNull(interval, "interval");
    Objects.requireNonNull(cfg, "cfg");
    Objects.requireNonNull(rm, "rm");
    if (!strategiesBySymbol.keySet().containsAll(candlesBySymbol.keySet())) {
      throw new IllegalArgumentException("every symbol in candlesBySymbol needs a strategy");
    }

    Set<String> symbols = candlesBySymbol.keySet();
    BigDecimal singleLegAdverse = cfg.fees().totalAdverseFactor(IndicatorMath.MC);
    BigDecimal liquidationAdverse = FeeModel.taker().totalAdverseFactor(IndicatorMath.MC);

    Map<String, Map<Instant, Candle>> bySymbolTime = new HashMap<>();
    TreeSet<Instant> allTimes = new TreeSet<>();
    for (String symbol : symbols) {
      Map<Instant, Candle> byTime = new HashMap<>();
      for (Candle c : candlesBySymbol.get(symbol)) {
        byTime.put(c.openTime(), c);
        allTimes.add(c.openTime());
      }
      bySymbolTime.put(symbol, byTime);
    }

    Map<String, Deque<Candle>> windows = new HashMap<>();
    Map<String, Deque<FundingEvent>> fundingWindows = new HashMap<>();
    Map<String, Integer> fundingIdx = new HashMap<>();
    Map<String, SymbolPosition> positions = new HashMap<>();
    Map<String, BigDecimal> lastNotional = new HashMap<>();
    Map<String, Candle> lastCandle = new HashMap<>();
    Map<String, List<Trade>> tradesBySymbol = new HashMap<>();
    Map<String, Long> barsSkipped = new HashMap<>();
    for (String symbol : symbols) {
      windows.put(symbol, new ArrayDeque<>());
      fundingWindows.put(symbol, new ArrayDeque<>());
      fundingIdx.put(symbol, 0);
      tradesBySymbol.put(symbol, new ArrayList<>());
      barsSkipped.put(symbol, 0L);
    }

    BigDecimal cash = cfg.startingCash();
    BigDecimal peakEquity = cfg.startingCash();
    boolean cbTripped = false;
    long evaluations = 0;
    long signals = 0;
    long rmRefusals = 0;
    List<Refusal> refusals = new ArrayList<>();
    List<EquityPoint> equityCurve = new ArrayList<>();

    for (Instant t : allTimes) {
      for (String symbol : symbols) {
        Candle candle = bySymbolTime.get(symbol).get(t);
        if (candle == null) {
          barsSkipped.merge(symbol, 1L, Long::sum);
          continue;
        }
        lastCandle.put(symbol, candle);

        // Circuit breaker sweep: liquidate this symbol at its first bar's open since the trip.
        if (cbTripped && positions.containsKey(symbol)) {
          cash =
              closePosition(
                  cash,
                  positions.get(symbol),
                  candle.open(),
                  liquidationAdverse,
                  t,
                  ExitReason.STOP_LOSS,
                  tradesBySymbol.get(symbol));
          positions.remove(symbol);
          lastNotional.remove(symbol);
        }

        // Funding accrual for a still-open position.
        List<FundingEvent> fundingList = fundingBySymbol.getOrDefault(symbol, List.of());
        int idx = fundingIdx.get(symbol);
        while (idx < fundingList.size()
            && !fundingList.get(idx).time().isAfter(candle.openTime())) {
          FundingEvent fe = fundingList.get(idx++);
          fundingWindows.get(symbol).addLast(fe);
          SymbolPosition pos = positions.get(symbol);
          if (pos != null) {
            cash =
                cash.add(
                    pos.size()
                        .multiply(fe.rate(), IndicatorMath.MC)
                        .multiply(candle.close(), IndicatorMath.MC));
          }
        }
        fundingIdx.put(symbol, idx);

        // Slide this symbol's window.
        Deque<Candle> window = windows.get(symbol);
        if (window.size() >= cfg.warmupBars()) {
          window.pollFirst();
        }
        window.addLast(candle);

        // Refresh this symbol's mark for cross-symbol leverage snapshots.
        SymbolPosition pos = positions.get(symbol);
        if (pos != null) {
          lastNotional.put(symbol, pos.size().multiply(candle.close(), IndicatorMath.MC));
        }

        if (window.size() < cfg.warmupBars()) {
          continue;
        }

        evaluations++;
        // PLAN-010 Task D3 scope note: cfg.exchangeFilters() is deliberately NOT threaded through
        // here, unlike the single-symbol harnesses -- one BacktestConfig is shared across every
        // symbol in a portfolio run, and a single ExchangeFilters value cannot correctly represent
        // per-symbol minNotional/stepSize/tickSize (they differ pair to pair on a real exchange).
        // Per-symbol filters would need a Map<String, ExchangeFilters> threaded through this
        // harness's own constructor -- left for a future plan; the live loop (Phase 1,
        // single-symbol per CLAUDE.md §4) is D1/D2's actual target and is unaffected by this gap.
        MarketContext ctx =
            new MarketContext(
                symbol,
                interval,
                new ArrayList<>(window),
                new ArrayList<>(fundingWindows.get(symbol)));
        Optional<TechnicalSignal> sig = strategiesBySymbol.get(symbol).evaluate(ctx);
        if (sig.isEmpty()) {
          continue;
        }
        signals++;
        TechnicalSignal signal = sig.get();
        if (signal.direction() == Direction.HOLD) {
          continue;
        }

        BigDecimal equityNow = computeEquity(cash, lastNotional);
        PortfolioState state =
            new PortfolioState(
                cash,
                equityNow,
                peakEquity.max(equityNow),
                Map.copyOf(lastNotional),
                cbTripped,
                Optional.empty());
        ExecutionDecision decision = rm.gate(signal, state, ctx);

        if (decision instanceof ExecutionDecision.Execute exec) {
          if (exec.side() == Direction.BUY && !positions.containsKey(symbol)) {
            // PLAN-012 Task D (review finding F11): exec.notional() is exact here (not just
            // "consistent by accident") because ctx.candles().getLast() == candle -- the RM sized
            // against this exact bar's close, same as every re-derivation below used to assume.
            BigDecimal entryFee = exec.notional().multiply(singleLegAdverse, IndicatorMath.MC);
            BigDecimal cashBeforeEntry = cash;
            cash =
                cash.subtract(exec.notional(), IndicatorMath.MC)
                    .subtract(entryFee, IndicatorMath.MC);
            positions.put(
                symbol, new SymbolPosition(exec.size(), candle.close(), t, cashBeforeEntry));
            lastNotional.put(symbol, exec.notional());
          } else if (exec.side() == Direction.SELL && positions.containsKey(symbol)) {
            cash =
                closePosition(
                    cash,
                    positions.get(symbol),
                    candle.close(),
                    singleLegAdverse,
                    t,
                    ExitReason.SIGNAL,
                    tradesBySymbol.get(symbol));
            positions.remove(symbol);
            lastNotional.remove(symbol);
          }
          // BUY-while-already-open / SELL-while-flat: silently not actioned, matching
          // BacktestHarness/FundingArbHarness's existing single-symbol convention. The RM's own
          // NO_OPEN_POSITION_TO_CLOSE check already prevents SELL-while-flat from reaching here.
        } else {
          rmRefusals++;
          ExecutionDecision.Refuse refuse = (ExecutionDecision.Refuse) decision;
          refusals.add(
              new Refusal(
                  refuse.asOf(),
                  symbol,
                  signal.direction(),
                  refuse.reason(),
                  refuse.explanation()));
        }
      }

      BigDecimal equity = computeEquity(cash, lastNotional);
      equityCurve.add(new EquityPoint(t, equity));
      if (!cbTripped) {
        if (equity.compareTo(peakEquity) > 0) {
          peakEquity = equity;
        }
        if (CircuitBreaker.shouldTrip(
            equity, peakEquity, rm.riskParameters().maxPortfolioDrawdownPct())) {
          cbTripped = true;
        }
      }
    }

    // End-of-data sweep: close whatever is still open, at each symbol's own last candle.
    for (String symbol : symbols) {
      SymbolPosition pos = positions.get(symbol);
      Candle last = lastCandle.get(symbol);
      if (pos != null && last != null) {
        cash =
            closePosition(
                cash,
                pos,
                last.close(),
                singleLegAdverse,
                last.openTime(),
                ExitReason.END_OF_DATA,
                tradesBySymbol.get(symbol));
      }
    }

    List<Trade> allTrades = new ArrayList<>();
    for (List<Trade> ts : tradesBySymbol.values()) {
      allTrades.addAll(ts);
    }
    allTrades.sort((a, b) -> a.entryTime().compareTo(b.entryTime()));

    BigDecimal endingEquity = cash;
    BigDecimal totalReturn =
        cfg.startingCash().signum() == 0
            ? BigDecimal.ZERO
            : endingEquity
                .subtract(cfg.startingCash())
                .divide(cfg.startingCash(), IndicatorMath.MC);
    double sharpe = Metrics.annualisedSharpe(equityCurve);
    BigDecimal maxDd = Metrics.maxDrawdown(equityCurve);
    double winRate = Metrics.winRate(allTrades);

    Map<String, Object> diag = new HashMap<>();
    diag.put("evaluations", evaluations);
    diag.put("signals", signals);
    diag.put("bars", (long) equityCurve.size());
    diag.put("rmRefusals", rmRefusals);
    diag.put("rmGated", true);
    diag.put("harness", "portfolio");
    diag.put("symbols", symbols.size());
    for (String symbol : symbols) {
      diag.put("barsSkipped." + symbol, barsSkipped.get(symbol));
      diag.put("trades." + symbol, (long) tradesBySymbol.get(symbol).size());
      // PLAN-013 Task G: per-symbol PnL -- Trade itself carries no symbol field, so this is the
      // only place cross-pair sign consistency (PanelCalibrationHarness) can be derived from
      // without re-running the backtest per symbol just to recover the association.
      BigDecimal symbolPnl = BigDecimal.ZERO;
      for (Trade t : tradesBySymbol.get(symbol)) {
        symbolPnl = symbolPnl.add(t.pnl(), IndicatorMath.MC);
      }
      diag.put("pnl." + symbol, symbolPnl);
    }

    return new BacktestResult(
        cfg.startingCash(),
        endingEquity,
        totalReturn,
        sharpe,
        maxDd,
        winRate,
        allTrades.size(),
        allTrades,
        equityCurve,
        diag,
        refusals);
  }

  /**
   * Cross-sectional rebalancing entry point (PLAN-015 Task B): evaluates one {@link
   * PortfolioStrategy} against the whole symbol universe each bar and rebalances toward its
   * declared {@link TargetPosition} weights, instead of evaluating one {@link TradingStrategy} per
   * symbol independently as {@link #run} does.
   *
   * <p><strong>Scope (long-only, Phase 1, single-leg):</strong> every {@link TargetPosition} this
   * strategy returns must have {@link PositionShape#SPOT_ONLY} or {@link PositionShape#SPOT_LONG}
   * shape and a non-negative weight — this harness throws {@link IllegalArgumentException}
   * immediately otherwise, rather than silently misrepresenting a target it cannot execute. There
   * is no short-selling execution path anywhere in this codebase yet ({@link
   * app.viglide.core.risk.RiskManager}'s own gate Javadoc: "Phase-1 long-only exchange; no
   * shorts"), so {@link PositionShape#PERP_SHORT} targets cannot be honoured here. {@link
   * PositionShape#DELTA_NEUTRAL_CARRY} targets need the paired spot+perp accounting {@link
   * PortfolioFundingArbHarnessV2} implements — a target-weight extension of that harness is a
   * separate, not-yet-built piece of PLAN-015 Task B (see {@code docs/STATUS.md}), deliberately not
   * folded into this method.
   *
   * <p><strong>How "rebalance toward targets" is implemented:</strong> each bar, {@code strategy}
   * is evaluated once against every symbol warm enough to have a full window (not per-symbol, as in
   * {@link #run}). {@code desiredNotional(symbol) = targetWeight × allocatedCapital} (zero for a
   * symbol absent from the result, per {@link TargetPosition}'s "absence means flat" contract) is
   * diffed against the symbol's last marked notional; a symbol only trades this bar if that
   * difference exceeds {@code noTradeBand × allocatedCapital}. A triggered symbol is rebalanced by
   * <strong>closing whatever is open, then — if the new target is non-zero — reopening
   * fresh</strong> rather than resizing an existing lot in place, and this applies equally whether
   * the target grew or shrank: an <em>increase</em> to an already-open position closes the old lot
   * (realising its P&amp;L via a real {@link Trade} record) before opening the new size, exactly
   * like a decrease does — it is never a silent overwrite of the open lot's cost basis. {@link
   * app.viglide.core.risk.RiskManager}'s sizing formula (ATR/risk-per-trade based) has no notion of
   * "add this much more to an existing position," and reusing it unchanged for a fresh open on
   * every rebalance keeps this harness faithful to every other harness's sizing precedent (the Risk
   * Manager, not the strategy or this harness, owns position magnitude — see {@code CLAUDE.md}
   * §7/§11). {@code desiredNotional} itself only ever decides <em>direction</em> and <em>whether to
   * trade</em>, never the traded size.
   *
   * <p><strong>Synthesised signal confidence is fixed at {@code 1.0}</strong> for every symbol this
   * bar decides to trade, deliberately not {@code |targetWeight|}: {@link
   * app.viglide.core.risk.RiskManager}'s confidence floor is binary (pass/fail against a threshold,
   * never a continuous size multiplier), and a cross-sectional strategy's per-symbol weight is an
   * <em>allocation</em> fraction, not a conviction score — an equal-weighted top-12 book assigns
   * each symbol {@code ≈0.083}, which would spuriously fail a default {@code 0.5} confidence floor
   * if weight were reused as confidence, silently breaking every equal-weight ranking strategy. A
   * symbol's presence in the target list (or, for an implicit-absence close, the fact that the
   * strategy considered the whole universe and chose not to mention it) is itself the conviction
   * signal here; magnitude is handled entirely by the notional-diff/no-trade-band logic above.
   *
   * <p><strong>Book-level proportional scale-down (the Task B trap):</strong> every candidate
   * symbol is gated against the <em>same</em> start-of-bar {@link PortfolioState} snapshot — none
   * of this bar's own trades are applied between gate calls — so {@link
   * app.viglide.core.risk.RiskManager#gate}'s own per-call leverage check cannot see what the
   * <em>other</em> candidates this bar are about to do. After collecting every symbol's individual
   * {@link ExecutionDecision.Execute} (closes applied first, freeing leverage headroom), this
   * method separately checks the book-wide hypothetical total notional if every remaining open were
   * applied at full size; if that would breach {@link
   * app.viglide.core.risk.RiskParameters#maxLeverage()} or {@link
   * app.viglide.core.risk.RiskParameters#maxTotalDeployedAbs()}, every open this bar is scaled down
   * by the same common factor rather than admitting some symbols at full size and refusing others
   * outright by iteration order — refusing asymmetrically is exactly what would quietly turn an
   * intended market-neutral or evenly-diversified book into a directional one.
   *
   * @param candlesBySymbol candles per symbol, ascending {@code openTime}, same shape as {@link
   *     #run}
   * @param strategy evaluated once per bar against every warm symbol's {@link MarketContext}
   * @param interval the decision cadence every symbol is evaluated at
   * @param cfg {@code minHoldBars}/{@code exchangeFilters} are not consulted by this method
   * @param rm mandatory — CLAUDE.md §11 forbids an ungated order path, target-weight mode included
   * @param allocatedCapital the fixed capital base {@code targetWeight} is a fraction of — distinct
   *     from {@code cfg.startingCash()}, which still governs the harness's actual cash ledger; a
   *     strategy allocated less than the full starting cash is a deliberate, supported case
   * @param noTradeBand fraction of {@code allocatedCapital} the strategy's own desired notional for
   *     a symbol must move, since the last time this harness actually rebalanced it, before this
   *     harness bothers rebalancing again (PLAN-015 Task B: "a real parameter, belongs in the
   *     calibration space" — this method takes it as a plain argument, calibration search over it
   *     is a caller concern). <strong>Deliberately not compared against the Risk Manager's approved
   *     notional</strong> (PLAN-019 Task C/D, {@code
   *     docs/notes/2026-08-07-plan019-runtargets-notradeband-fix.md}): the RM caps every position
   *     at {@code maxPositionPct × equity}, which for any strategy targeting more than that per
   *     symbol would create a permanent gap between intent and approved size that no band value
   *     could ever suppress — every held position would be re-litigated against that gap every bar.
   */
  public static BacktestResult runTargets(
      Map<String, List<Candle>> candlesBySymbol,
      PortfolioStrategy strategy,
      CandleInterval interval,
      BacktestConfig cfg,
      RiskManagerPort rm,
      BigDecimal allocatedCapital,
      BigDecimal noTradeBand) {
    Objects.requireNonNull(candlesBySymbol, "candlesBySymbol");
    Objects.requireNonNull(strategy, "strategy");
    Objects.requireNonNull(interval, "interval");
    Objects.requireNonNull(cfg, "cfg");
    Objects.requireNonNull(rm, "rm");
    Objects.requireNonNull(allocatedCapital, "allocatedCapital");
    Objects.requireNonNull(noTradeBand, "noTradeBand");
    if (candlesBySymbol.isEmpty()) {
      throw new IllegalArgumentException("candlesBySymbol must not be empty");
    }
    if (allocatedCapital.signum() <= 0) {
      throw new IllegalArgumentException("allocatedCapital must be > 0");
    }
    if (noTradeBand.signum() < 0) {
      throw new IllegalArgumentException("noTradeBand must be >= 0, got: " + noTradeBand);
    }

    Set<String> symbols = new TreeSet<>(candlesBySymbol.keySet());
    BigDecimal singleLegAdverse = cfg.fees().totalAdverseFactor(IndicatorMath.MC);
    BigDecimal noTradeBandNotional = noTradeBand.multiply(allocatedCapital, IndicatorMath.MC);

    Map<String, Map<Instant, Candle>> bySymbolTime = new HashMap<>();
    TreeSet<Instant> allTimes = new TreeSet<>();
    for (String symbol : symbols) {
      Map<Instant, Candle> byTime = new HashMap<>();
      for (Candle c : candlesBySymbol.get(symbol)) {
        byTime.put(c.openTime(), c);
        allTimes.add(c.openTime());
      }
      bySymbolTime.put(symbol, byTime);
    }

    Map<String, Deque<Candle>> windows = new HashMap<>();
    Map<String, SymbolPosition> positions = new HashMap<>();
    Map<String, BigDecimal> lastNotional = new HashMap<>();
    // The rebalance trigger's memory (docs/notes/2026-08-07-plan019-runtargets-notradeband-fix.md):
    // the strategy's own last-acted-on desired notional, NOT what the Risk Manager actually
    // approved. Comparing against the RM's capped `current` created a permanent gap for any
    // strategy whose target exceeds the RM's per-position cap -- no noTradeBand value could ever
    // suppress the resulting every-bar re-churn. lastDesired updates only on a successful
    // close/open this bar (see the two update sites below), never every bar and never on a
    // refusal, so a refused rebalance is retried next bar instead of silently forgotten.
    Map<String, BigDecimal> lastDesired = new HashMap<>();
    Map<String, Candle> lastCandle = new HashMap<>();
    Map<String, List<Trade>> tradesBySymbol = new HashMap<>();
    Map<String, Long> barsSkipped = new HashMap<>();
    for (String symbol : symbols) {
      windows.put(symbol, new ArrayDeque<>());
      tradesBySymbol.put(symbol, new ArrayList<>());
      barsSkipped.put(symbol, 0L);
    }

    BigDecimal cash = cfg.startingCash();
    BigDecimal peakEquity = cfg.startingCash();
    boolean cbTripped = false;
    long evaluations = 0;
    long rmRefusals = 0;
    long bookScaleDowns = 0;
    long noTradeBandSkips = 0;
    List<Refusal> refusals = new ArrayList<>();
    List<EquityPoint> equityCurve = new ArrayList<>();

    for (Instant t : allTimes) {
      Map<String, MarketContext> warmContexts = new TreeMap<>();
      for (String symbol : symbols) {
        Candle candle = bySymbolTime.get(symbol).get(t);
        if (candle == null) {
          barsSkipped.merge(symbol, 1L, Long::sum);
          continue;
        }
        lastCandle.put(symbol, candle);

        if (cbTripped && positions.containsKey(symbol)) {
          cash =
              closePosition(
                  cash,
                  positions.get(symbol),
                  candle.open(),
                  FeeModel.taker().totalAdverseFactor(IndicatorMath.MC),
                  t,
                  ExitReason.STOP_LOSS,
                  tradesBySymbol.get(symbol));
          positions.remove(symbol);
          lastNotional.remove(symbol);
          lastDesired.remove(symbol);
        }

        Deque<Candle> window = windows.get(symbol);
        if (window.size() >= cfg.warmupBars()) {
          window.pollFirst();
        }
        window.addLast(candle);

        SymbolPosition pos = positions.get(symbol);
        if (pos != null) {
          lastNotional.put(symbol, pos.size().multiply(candle.close(), IndicatorMath.MC));
        }

        if (window.size() >= cfg.warmupBars()) {
          warmContexts.put(symbol, new MarketContext(symbol, interval, new ArrayList<>(window)));
        }
      }

      if (!cbTripped && !warmContexts.isEmpty()) {
        evaluations++;
        PortfolioContext portfolioContext = new PortfolioContext(t, warmContexts);
        List<TargetPosition> targets = strategy.evaluate(portfolioContext);
        Map<String, TargetPosition> targetsBySymbol = new TreeMap<>();
        for (TargetPosition tp : targets) {
          if (tp.shape() != PositionShape.SPOT_ONLY && tp.shape() != PositionShape.SPOT_LONG) {
            throw new IllegalArgumentException(
                "runTargets only supports SPOT_ONLY/SPOT_LONG targets (long-only, Phase 1); got "
                    + tp.shape()
                    + " for "
                    + tp.symbol()
                    + " -- DELTA_NEUTRAL_CARRY needs the two-leg harness, PERP_SHORT needs a"
                    + " short-selling execution path that does not exist yet");
          }
          if (tp.targetWeight().signum() < 0) {
            throw new IllegalArgumentException(
                "runTargets is long-only (Phase 1): negative targetWeight for "
                    + tp.symbol()
                    + " has no execution path");
          }
          targetsBySymbol.put(tp.symbol(), tp);
        }

        BigDecimal equityNow = computeEquity(cash, lastNotional);
        PortfolioState startOfBarState =
            new PortfolioState(
                cash,
                equityNow,
                peakEquity.max(equityNow),
                Map.copyOf(lastNotional),
                false,
                Optional.empty());

        // Every symbol warm enough to have been evaluated is a rebalance candidate -- including
        // ones absent from `targetsBySymbol` (implicit flat) if they currently hold a position.
        List<ExecutionDecision.Execute> closesToApply = new ArrayList<>();
        List<String> closeSymbols = new ArrayList<>();
        List<ExecutionDecision.Execute> opensToApply = new ArrayList<>();
        List<String> openSymbols = new ArrayList<>();
        Map<String, BigDecimal> desiredBySymbol = new HashMap<>();

        for (String symbol : warmContexts.keySet()) {
          BigDecimal desired =
              Optional.ofNullable(targetsBySymbol.get(symbol))
                  .map(tp -> tp.targetWeight().multiply(allocatedCapital, IndicatorMath.MC))
                  .orElse(BigDecimal.ZERO);
          // `current` (the RM-approved, capped notional) is still used below purely as an
          // informational value in the synthesised signal's explanation text -- the trigger
          // decision itself compares against `lastDesired`, never `current`. See the class-field
          // comment on `lastDesired` for why.
          BigDecimal current = lastNotional.getOrDefault(symbol, BigDecimal.ZERO);
          BigDecimal previousDesired = lastDesired.getOrDefault(symbol, BigDecimal.ZERO);
          BigDecimal delta = desired.subtract(previousDesired, IndicatorMath.MC).abs();
          if (delta.compareTo(noTradeBandNotional) <= 0) {
            noTradeBandSkips++;
            continue;
          }

          MarketContext symbolCtx = warmContexts.get(symbol);

          // A rebalanced symbol is ALWAYS closed-then-reopened, never resized in place -- this
          // applies equally to an increase (desired > current > 0) and a decrease, not just a
          // decrease-to-zero: RiskManager has no notion of "add this much more," so an existing
          // lot's cost basis must be realised via a real close before any new size is opened,
          // rather than silently overwritten (which would leak the old lot's cash accounting).
          if (positions.containsKey(symbol)) {
            TechnicalSignal closeSignal =
                targetWeightSignal(symbol, Direction.SELL, desired, current, t);
            ExecutionDecision decision = rm.gate(closeSignal, startOfBarState, symbolCtx);
            if (decision instanceof ExecutionDecision.Execute exec) {
              closesToApply.add(exec);
              closeSymbols.add(symbol);
            } else {
              rmRefusals++;
              ExecutionDecision.Refuse refuse = (ExecutionDecision.Refuse) decision;
              refusals.add(
                  new Refusal(
                      refuse.asOf(),
                      symbol,
                      Direction.SELL,
                      refuse.reason(),
                      refuse.explanation()));
              // The old lot could not be closed (e.g. confidence floor/stale input) -- do not
              // attempt to open a new size on top of a lot that failed to close; try again next
              // bar.
              continue;
            }
          }

          if (desired.signum() > 0) {
            TechnicalSignal openSignal =
                targetWeightSignal(symbol, Direction.BUY, desired, current, t);
            ExecutionDecision decision = rm.gate(openSignal, startOfBarState, symbolCtx);
            if (decision instanceof ExecutionDecision.Execute exec) {
              opensToApply.add(exec);
              openSymbols.add(symbol);
              // Raw, pre-scale-down desired -- lastDesired must record what the strategy asked
              // for, not what the book-level scale-down below ends up actually delivering, or the
              // exact same re-litigation bug reappears for that cap instead of the RM's.
              desiredBySymbol.put(symbol, desired);
            } else {
              rmRefusals++;
              ExecutionDecision.Refuse refuse = (ExecutionDecision.Refuse) decision;
              refusals.add(
                  new Refusal(
                      refuse.asOf(), symbol, Direction.BUY, refuse.reason(), refuse.explanation()));
            }
          }
        }

        // Apply closes first -- frees leverage headroom before the aggregate open check below.
        for (int i = 0; i < closeSymbols.size(); i++) {
          String symbol = closeSymbols.get(i);
          cash =
              closePosition(
                  cash,
                  positions.get(symbol),
                  lastCandle.get(symbol).close(),
                  singleLegAdverse,
                  t,
                  ExitReason.SIGNAL,
                  tradesBySymbol.get(symbol));
          positions.remove(symbol);
          lastNotional.remove(symbol);
          // If this symbol also reopens this bar, the open-application loop below re-sets
          // lastDesired to the new target; if not (a pure close), it correctly stays absent.
          lastDesired.remove(symbol);
        }

        // Book-level proportional scale-down (Task B trap): every open above was individually
        // approved against the SAME start-of-bar state, so their combined effect was never
        // checked together. Recompute the aggregate now, using rm.riskParameters() directly --
        // RiskManager.gate() itself is never called a second time or reimplemented here.
        if (!opensToApply.isEmpty()) {
          BigDecimal totalAfterCloses = computeEquity(BigDecimal.ZERO, lastNotional);
          BigDecimal sumOfOpens = BigDecimal.ZERO;
          for (ExecutionDecision.Execute exec : opensToApply) {
            sumOfOpens = sumOfOpens.add(exec.notional(), IndicatorMath.MC);
          }
          BigDecimal maxAllowed =
              equityNow.multiply(rm.riskParameters().maxLeverage(), IndicatorMath.MC);
          if (rm.riskParameters().maxTotalDeployedAbs().isPresent()) {
            maxAllowed = maxAllowed.min(rm.riskParameters().maxTotalDeployedAbs().get());
          }
          BigDecimal hypotheticalTotal = totalAfterCloses.add(sumOfOpens, IndicatorMath.MC);
          BigDecimal scaleFactor = BigDecimal.ONE;
          if (hypotheticalTotal.compareTo(maxAllowed) > 0) {
            BigDecimal availableRoom = maxAllowed.subtract(totalAfterCloses, IndicatorMath.MC);
            scaleFactor =
                availableRoom.signum() <= 0
                    ? BigDecimal.ZERO
                    : availableRoom.divide(sumOfOpens, IndicatorMath.MC).min(BigDecimal.ONE);
            bookScaleDowns++;
          }

          for (int i = 0; i < openSymbols.size(); i++) {
            String symbol = openSymbols.get(i);
            ExecutionDecision.Execute exec = opensToApply.get(i);
            BigDecimal scaledNotional = exec.notional().multiply(scaleFactor, IndicatorMath.MC);
            if (scaledNotional.signum() <= 0) {
              refusals.add(
                  new Refusal(
                      t,
                      symbol,
                      Direction.BUY,
                      ExecutionDecision.RefusalReason.LEVERAGE_CAP_EXCEEDED,
                      "book-level proportional scale-down reduced this open to zero"
                          + " (aggregate open notional would have exceeded the leverage/absolute"
                          + " cap)"));
              continue;
            }
            BigDecimal scaledSize = exec.size().multiply(scaleFactor, IndicatorMath.MC);
            BigDecimal entryFee = scaledNotional.multiply(singleLegAdverse, IndicatorMath.MC);
            BigDecimal cashBeforeEntry = cash;
            cash =
                cash.subtract(scaledNotional, IndicatorMath.MC)
                    .subtract(entryFee, IndicatorMath.MC);
            Candle candle = lastCandle.get(symbol);
            positions.put(
                symbol, new SymbolPosition(scaledSize, candle.close(), t, cashBeforeEntry));
            lastNotional.put(symbol, scaledNotional);
            lastDesired.put(symbol, desiredBySymbol.get(symbol));
          }
        }
      }

      BigDecimal equity = computeEquity(cash, lastNotional);
      equityCurve.add(new EquityPoint(t, equity));
      if (!cbTripped) {
        if (equity.compareTo(peakEquity) > 0) {
          peakEquity = equity;
        }
        if (CircuitBreaker.shouldTrip(
            equity, peakEquity, rm.riskParameters().maxPortfolioDrawdownPct())) {
          cbTripped = true;
        }
      }
    }

    // End-of-data sweep: close whatever is still open, at each symbol's own last candle.
    for (String symbol : symbols) {
      SymbolPosition pos = positions.get(symbol);
      Candle last = lastCandle.get(symbol);
      if (pos != null && last != null) {
        cash =
            closePosition(
                cash,
                pos,
                last.close(),
                singleLegAdverse,
                last.openTime(),
                ExitReason.END_OF_DATA,
                tradesBySymbol.get(symbol));
      }
    }

    List<Trade> allTrades = new ArrayList<>();
    for (List<Trade> ts : tradesBySymbol.values()) {
      allTrades.addAll(ts);
    }
    allTrades.sort((a, b) -> a.entryTime().compareTo(b.entryTime()));

    BigDecimal endingEquity = cash;
    BigDecimal totalReturn =
        cfg.startingCash().signum() == 0
            ? BigDecimal.ZERO
            : endingEquity
                .subtract(cfg.startingCash())
                .divide(cfg.startingCash(), IndicatorMath.MC);
    double sharpe = Metrics.annualisedSharpe(equityCurve);
    BigDecimal maxDd = Metrics.maxDrawdown(equityCurve);
    double winRate = Metrics.winRate(allTrades);

    Map<String, Object> diag = new HashMap<>();
    diag.put("evaluations", evaluations);
    diag.put("bars", (long) equityCurve.size());
    diag.put("rmRefusals", rmRefusals);
    diag.put("rmGated", true);
    diag.put("harness", "portfolio-targets");
    diag.put("symbols", symbols.size());
    diag.put("bookScaleDowns", bookScaleDowns);
    diag.put("noTradeBandSkips", noTradeBandSkips);
    diag.put("allocatedCapital", allocatedCapital);
    diag.put("noTradeBand", noTradeBand);
    for (String symbol : symbols) {
      diag.put("barsSkipped." + symbol, barsSkipped.get(symbol));
      diag.put("trades." + symbol, (long) tradesBySymbol.get(symbol).size());
      BigDecimal symbolPnl = BigDecimal.ZERO;
      for (Trade tr : tradesBySymbol.get(symbol)) {
        symbolPnl = symbolPnl.add(tr.pnl(), IndicatorMath.MC);
      }
      diag.put("pnl." + symbol, symbolPnl);
    }

    return new BacktestResult(
        cfg.startingCash(),
        endingEquity,
        totalReturn,
        sharpe,
        maxDd,
        winRate,
        allTrades.size(),
        allTrades,
        equityCurve,
        diag,
        refusals);
  }

  /**
   * Two-leg-capable counterpart of {@link #runTargets} (PLAN-019 Task C): executes a {@link
   * PortfolioStrategy}'s {@link PositionShape#DELTA_NEUTRAL_CARRY} targets as a real paired
   * spot+perp book — long spot, short perp, margin, per-symbol liquidation guard, funding accrual —
   * sharing one cash balance, one Risk Manager gate, and book-level proportional scale-down with
   * every {@link PositionShape#SPOT_ONLY}/{@link PositionShape#SPOT_LONG} target evaluated in the
   * <em>same</em> book, which keep executing through the original single-leg mechanics. {@link
   * PositionShape#PERP_SHORT} and a negative {@code SPOT_ONLY} weight still have no execution path
   * anywhere in this codebase and fail loudly, exactly like {@link #runTargets}.
   *
   * <p>The two-leg accounting itself — margin, the liquidation guard, funding accrual, the
   * spot/perp close formula — is not reimplemented here: every carry position open, close, and mark
   * calls into {@link PortfolioFundingArbHarnessV2}'s own (package-private, PLAN-019 Task C) {@code
   * OpenPosition}, {@code close}, and {@code computeEquity}, so this method and that harness cannot
   * silently drift apart on what a carry position is worth or when it liquidates.
   *
   * <p><strong>Which symbols may be carry-traded is declared by data, not inferred from strategy
   * output alone:</strong> a symbol is carry-capable only if it has a key in {@code
   * spotCandlesBySymbol} — a {@link PositionShape#DELTA_NEUTRAL_CARRY} target for any other symbol
   * throws {@link IllegalArgumentException} immediately, the same "fail loud, never silently
   * misrepresent" rule {@link #runTargets} already applies to {@link PositionShape#PERP_SHORT}. A
   * caller declares a symbol carry-eligible once, by supplying its spot series, rather than the
   * harness inferring intent from whatever a strategy happens to return on a given bar.
   *
   * <p><strong>Bar tolerance for a carry-capable symbol:</strong> mirrors {@link
   * PortfolioFundingArbHarnessV2} exactly — a bar missing either that symbol's perp candle or its
   * spot candle is skipped for that symbol only ({@code barsSkipped.<SYMBOL>}), whether or not it
   * currently holds an open position; an open position simply carries over to the next bar both
   * legs are available. A non-carry-capable symbol is unaffected by spot data gaps, since it never
   * looks at {@code spotCandlesBySymbol} at all.
   *
   * <p><strong>Sizing:</strong> exactly like {@link #runTargets}, {@code targetWeight} only ever
   * decides direction and whether to rebalance (via the no-trade band) — the traded size comes from
   * the Risk Manager's own ATR-based sizing on the synthesised signal, then book-level scale-down
   * if the aggregate breaches the leverage/absolute cap. For a carry leg, the RM sizes against the
   * perp decision window (entry price = that window's last close), and the traded quantity is then
   * re-derived against the spot close — {@code q = scaledNotional / spot.close()} — never against
   * {@code exec.size()} directly, for the identical reason {@link PortfolioFundingArbHarnessV2#run}
   * does: {@code exec.size()} is implicitly perp-priced, and spot and perp are never assumed equal
   * (that is the whole point of a basis trade).
   *
   * @param perpCandlesBySymbol decision window and, for a non-carry-capable symbol, the single
   *     execution price series — same role {@code candlesBySymbol} plays in {@link #runTargets}
   * @param spotCandlesBySymbol spot klines for carry-capable symbols only; joined to that symbol's
   *     perp candles by exact {@code openTime}
   * @param fundingBySymbol funding events per symbol, ascending {@code time}; windowed into every
   *     warm {@link MarketContext#fundingHistory()} (not just carry-capable symbols') so a {@code
   *     FUNDING_AWARE} strategy can rank on it before it has ever opened a position — a symbol
   *     absent from this map simply never accrues funding and sees an empty history
   * @param perpSubBarCandlesBySymbol optional finer-grained perp series per symbol (PLAN-009 Task
   *     B2) for the liquidation guard; a symbol absent keeps close-only liquidation timing
   * @param strategy evaluated once per bar against every warm symbol's {@link MarketContext}
   * @param interval the decision cadence every symbol is evaluated at
   * @param cfg {@code minHoldBars}/{@code exchangeFilters} are not consulted, same as {@link
   *     #runTargets}
   * @param rm mandatory — CLAUDE.md §11 forbids an ungated order path, two-leg mode included
   * @param allocatedCapital the fixed capital base {@code targetWeight} is a fraction of
   * @param noTradeBand fraction of {@code allocatedCapital} the strategy's own desired notional for
   *     a symbol must move, since the last time this harness actually rebalanced it, before this
   *     harness bothers rebalancing again — see the 7-argument {@link #runTargets}'s Javadoc on
   *     this same parameter for why it is compared against the strategy's own prior intent rather
   *     than the Risk Manager's approved notional
   */
  public static BacktestResult runTargets(
      Map<String, List<Candle>> perpCandlesBySymbol,
      Map<String, List<Candle>> spotCandlesBySymbol,
      Map<String, List<FundingEvent>> fundingBySymbol,
      Map<String, List<Candle>> perpSubBarCandlesBySymbol,
      PortfolioStrategy strategy,
      CandleInterval interval,
      BacktestConfig cfg,
      RiskManagerPort rm,
      BigDecimal allocatedCapital,
      BigDecimal noTradeBand) {
    Objects.requireNonNull(perpCandlesBySymbol, "perpCandlesBySymbol");
    Objects.requireNonNull(spotCandlesBySymbol, "spotCandlesBySymbol");
    Objects.requireNonNull(fundingBySymbol, "fundingBySymbol");
    Objects.requireNonNull(perpSubBarCandlesBySymbol, "perpSubBarCandlesBySymbol");
    Objects.requireNonNull(strategy, "strategy");
    Objects.requireNonNull(interval, "interval");
    Objects.requireNonNull(cfg, "cfg");
    Objects.requireNonNull(rm, "rm");
    Objects.requireNonNull(allocatedCapital, "allocatedCapital");
    Objects.requireNonNull(noTradeBand, "noTradeBand");
    if (perpCandlesBySymbol.isEmpty()) {
      throw new IllegalArgumentException("perpCandlesBySymbol must not be empty");
    }
    if (allocatedCapital.signum() <= 0) {
      throw new IllegalArgumentException("allocatedCapital must be > 0");
    }
    if (noTradeBand.signum() < 0) {
      throw new IllegalArgumentException("noTradeBand must be >= 0, got: " + noTradeBand);
    }

    Set<String> symbols = new TreeSet<>(perpCandlesBySymbol.keySet());
    Set<String> carryCapableSymbols = spotCandlesBySymbol.keySet();
    BigDecimal singleLegAdverse = cfg.fees().totalAdverseFactor(IndicatorMath.MC);
    BigDecimal liquidationAdverse = FeeModel.taker().totalAdverseFactor(IndicatorMath.MC);
    BigDecimal leverage = rm.riskParameters().maxLeverage();
    BigDecimal noTradeBandNotional = noTradeBand.multiply(allocatedCapital, IndicatorMath.MC);

    Map<String, Map<Instant, Candle>> perpBySymbolTime = new HashMap<>();
    Map<String, Map<Instant, Candle>> spotBySymbolTime = new HashMap<>();
    TreeSet<Instant> allTimes = new TreeSet<>();
    for (String symbol : symbols) {
      Map<Instant, Candle> perpByTime = new HashMap<>();
      for (Candle c : perpCandlesBySymbol.get(symbol)) {
        perpByTime.put(c.openTime(), c);
        allTimes.add(c.openTime());
      }
      perpBySymbolTime.put(symbol, perpByTime);
      Map<Instant, Candle> spotByTime = new HashMap<>();
      for (Candle c : spotCandlesBySymbol.getOrDefault(symbol, List.of())) {
        spotByTime.put(c.openTime(), c);
      }
      spotBySymbolTime.put(symbol, spotByTime);
    }

    Map<String, Deque<Candle>> windows = new HashMap<>();
    Map<String, Deque<FundingEvent>> fundingWindows = new HashMap<>();
    Map<String, Integer> fundingIdx = new HashMap<>();
    Map<String, Integer> subBarIdx = new HashMap<>();
    Map<String, SymbolPosition> positions = new HashMap<>();
    Map<String, PortfolioFundingArbHarnessV2.OpenPosition> carryPositions = new HashMap<>();
    Map<String, BigDecimal> lastNotional = new HashMap<>();
    // See the 7-argument runTargets's identical field for why this, not lastNotional, drives the
    // rebalance trigger (docs/notes/2026-08-07-plan019-runtargets-notradeband-fix.md).
    Map<String, BigDecimal> lastDesired = new HashMap<>();
    Map<String, Candle> lastCandle = new HashMap<>();
    Map<String, Candle> lastSpotCandle = new HashMap<>();
    Map<String, List<Trade>> tradesBySymbol = new HashMap<>();
    Map<String, Long> barsSkipped = new HashMap<>();
    for (String symbol : symbols) {
      windows.put(symbol, new ArrayDeque<>());
      fundingWindows.put(symbol, new ArrayDeque<>());
      fundingIdx.put(symbol, 0);
      subBarIdx.put(symbol, 0);
      tradesBySymbol.put(symbol, new ArrayList<>());
      barsSkipped.put(symbol, 0L);
    }

    BigDecimal cash = cfg.startingCash();
    BigDecimal peakEquity = cfg.startingCash();
    boolean cbTripped = false;
    long evaluations = 0;
    long rmRefusals = 0;
    long bookScaleDowns = 0;
    long noTradeBandSkips = 0;
    long liquidations = 0;
    BigDecimal totalFeesPaid = BigDecimal.ZERO;
    List<Refusal> refusals = new ArrayList<>();
    List<EquityPoint> equityCurve = new ArrayList<>();
    List<LiquidationEvent> liquidationEvents = new ArrayList<>();

    for (Instant t : allTimes) {
      Map<String, MarketContext> warmContexts = new TreeMap<>();
      for (String symbol : symbols) {
        Candle perp = perpBySymbolTime.get(symbol).get(t);
        if (perp == null) {
          barsSkipped.merge(symbol, 1L, Long::sum);
          continue;
        }
        boolean carryCapable = carryCapableSymbols.contains(symbol);
        Candle spot = carryCapable ? spotBySymbolTime.get(symbol).get(t) : null;
        if (carryCapable && spot == null) {
          barsSkipped.merge(symbol, 1L, Long::sum);
          continue;
        }
        lastCandle.put(symbol, perp);
        if (spot != null) {
          lastSpotCandle.put(symbol, spot);
        }

        // Circuit breaker sweep: liquidate this symbol's open leg(s) at this bar's open.
        if (cbTripped) {
          if (positions.containsKey(symbol)) {
            SymbolPosition pos = positions.get(symbol);
            BigDecimal exitFee =
                pos.size()
                    .multiply(perp.open(), IndicatorMath.MC)
                    .multiply(liquidationAdverse, IndicatorMath.MC);
            totalFeesPaid = totalFeesPaid.add(exitFee, IndicatorMath.MC);
            cash =
                closePosition(
                    cash,
                    pos,
                    perp.open(),
                    liquidationAdverse,
                    t,
                    ExitReason.STOP_LOSS,
                    tradesBySymbol.get(symbol));
            positions.remove(symbol);
            lastNotional.remove(symbol);
            lastDesired.remove(symbol);
          }
          if (carryPositions.containsKey(symbol)) {
            PortfolioFundingArbHarnessV2.CloseOutcome outcome =
                PortfolioFundingArbHarnessV2.close(
                    cash,
                    carryPositions.get(symbol),
                    spot.open(),
                    perp.open(),
                    liquidationAdverse,
                    t,
                    ExitReason.STOP_LOSS);
            cash = outcome.cash();
            tradesBySymbol.get(symbol).add(outcome.trade());
            totalFeesPaid = totalFeesPaid.add(outcome.feesPaid(), IndicatorMath.MC);
            carryPositions.remove(symbol);
            lastNotional.remove(symbol);
            lastDesired.remove(symbol);
          }
        }

        // Funding accrual for a still-open carry position -- also windows fundingHistory for every
        // symbol (even one never yet opened), so a FUNDING_AWARE strategy can rank on it before it
        // has ever held a position.
        List<FundingEvent> fundingList = fundingBySymbol.getOrDefault(symbol, List.of());
        int fIdx = fundingIdx.get(symbol);
        while (fIdx < fundingList.size()
            && !fundingList.get(fIdx).time().isAfter(perp.openTime())) {
          FundingEvent fe = fundingList.get(fIdx++);
          fundingWindows.get(symbol).addLast(fe);
          PortfolioFundingArbHarnessV2.OpenPosition pos = carryPositions.get(symbol);
          if (pos != null) {
            cash =
                cash.add(
                    pos.q()
                        .multiply(fe.rate(), IndicatorMath.MC)
                        .multiply(perp.close(), IndicatorMath.MC));
          }
        }
        fundingIdx.put(symbol, fIdx);

        // Slice this symbol's perp sub-bars (if any) covering this bar's window.
        List<Candle> perpSubBarCandles = perpSubBarCandlesBySymbol.getOrDefault(symbol, List.of());
        List<Candle> subBarSlice = List.of();
        if (!perpSubBarCandles.isEmpty()) {
          Instant windowEnd = perp.openTime().plus(interval.duration());
          int idx = subBarIdx.get(symbol);
          while (idx < perpSubBarCandles.size()
              && perpSubBarCandles.get(idx).openTime().isBefore(perp.openTime())) {
            idx++;
          }
          int sliceStart = idx;
          while (idx < perpSubBarCandles.size()
              && perpSubBarCandles.get(idx).openTime().isBefore(windowEnd)) {
            idx++;
          }
          subBarIdx.put(symbol, idx);
          subBarSlice = perpSubBarCandles.subList(sliceStart, idx);
          FundingArbHarnessV2.validatePerpSubBarsWithinParentRange(perp, subBarSlice, windowEnd);
        }

        // Liquidation guard -- every bar this symbol has a carry position open.
        PortfolioFundingArbHarnessV2.OpenPosition carryPos = carryPositions.get(symbol);
        if (carryPos != null) {
          List<Candle> liquidationChecks = subBarSlice.isEmpty() ? List.of(perp) : subBarSlice;
          for (Candle check : liquidationChecks) {
            carryPos = carryPositions.get(symbol);
            if (carryPos == null) break; // already liquidated by an earlier sub-bar this bar
            BigDecimal perpLoss =
                check
                    .close()
                    .subtract(carryPos.perpEntry(), IndicatorMath.MC)
                    .multiply(carryPos.q(), IndicatorMath.MC);
            BigDecimal marginThreshold =
                carryPos
                    .margin()
                    .multiply(FundingArbHarnessV2.LIQUIDATION_MARGIN_BUFFER, IndicatorMath.MC);
            if (perpLoss.signum() > 0 && perpLoss.compareTo(marginThreshold) >= 0) {
              BigDecimal thisSymbolNotional =
                  carryPos.q().multiply(check.close(), IndicatorMath.MC);
              BigDecimal bookNotional = thisSymbolNotional;
              for (Map.Entry<String, BigDecimal> e : lastNotional.entrySet()) {
                if (!e.getKey().equals(symbol)) {
                  bookNotional = bookNotional.add(e.getValue(), IndicatorMath.MC);
                }
              }
              BigDecimal equityAtLiquidation =
                  computeBookEquity(cash, positions, carryPositions, lastCandle, lastSpotCandle);
              double bookLeverageAtLiquidation =
                  equityAtLiquidation.signum() > 0
                      ? bookNotional.divide(equityAtLiquidation, IndicatorMath.MC).doubleValue()
                      : 0.0;
              liquidationEvents.add(
                  new LiquidationEvent(
                      symbol,
                      perp.openTime(),
                      perpLoss,
                      marginThreshold,
                      perpLoss.subtract(marginThreshold, IndicatorMath.MC),
                      bookNotional,
                      equityAtLiquidation,
                      bookLeverageAtLiquidation));

              PortfolioFundingArbHarnessV2.CloseOutcome outcome =
                  PortfolioFundingArbHarnessV2.close(
                      cash,
                      carryPos,
                      spot.close(),
                      check.close(),
                      liquidationAdverse,
                      perp.openTime(),
                      ExitReason.LIQUIDATION_GUARD);
              cash = outcome.cash();
              tradesBySymbol.get(symbol).add(outcome.trade());
              totalFeesPaid = totalFeesPaid.add(outcome.feesPaid(), IndicatorMath.MC);
              liquidations++;
              carryPositions.remove(symbol);
              lastNotional.remove(symbol);
            }
          }
        }

        // Slide this symbol's decision window (perp-based, same series a non-carry symbol trades).
        Deque<Candle> window = windows.get(symbol);
        if (window.size() >= cfg.warmupBars()) {
          window.pollFirst();
        }
        window.addLast(perp);

        // Refresh this symbol's mark for cross-symbol leverage/exposure snapshots.
        if (positions.containsKey(symbol)) {
          lastNotional.put(
              symbol, positions.get(symbol).size().multiply(perp.close(), IndicatorMath.MC));
        }
        PortfolioFundingArbHarnessV2.OpenPosition refreshedCarry = carryPositions.get(symbol);
        if (refreshedCarry != null) {
          lastNotional.put(symbol, refreshedCarry.q().multiply(perp.close(), IndicatorMath.MC));
        }

        if (window.size() >= cfg.warmupBars()) {
          warmContexts.put(
              symbol,
              new MarketContext(
                  symbol,
                  interval,
                  new ArrayList<>(window),
                  new ArrayList<>(fundingWindows.get(symbol))));
        }
      }

      if (!cbTripped && !warmContexts.isEmpty()) {
        evaluations++;
        PortfolioContext portfolioContext = new PortfolioContext(t, warmContexts);
        List<TargetPosition> targets = strategy.evaluate(portfolioContext);
        Map<String, TargetPosition> targetsBySymbol = new TreeMap<>();
        for (TargetPosition tp : targets) {
          if (tp.shape() == PositionShape.PERP_SHORT) {
            throw new IllegalArgumentException(
                "runTargets has no short-selling execution path for "
                    + tp.symbol()
                    + " -- PERP_SHORT needs one that does not exist yet");
          }
          if (tp.shape() == PositionShape.DELTA_NEUTRAL_CARRY
              && !carryCapableSymbols.contains(tp.symbol())) {
            throw new IllegalArgumentException(
                "DELTA_NEUTRAL_CARRY target for "
                    + tp.symbol()
                    + " but no spot data was supplied for it -- runTargets cannot execute a"
                    + " two-leg trade without a spot leg (supply it via spotCandlesBySymbol)");
          }
          if (tp.shape() == PositionShape.SPOT_ONLY && tp.targetWeight().signum() < 0) {
            throw new IllegalArgumentException(
                "runTargets is long-only for SPOT_ONLY (Phase 1): negative targetWeight for "
                    + tp.symbol()
                    + " has no execution path");
          }
          targetsBySymbol.put(tp.symbol(), tp);
        }

        BigDecimal equityNow =
            computeBookEquity(cash, positions, carryPositions, lastCandle, lastSpotCandle);
        PortfolioState startOfBarState =
            new PortfolioState(
                cash,
                equityNow,
                peakEquity.max(equityNow),
                Map.copyOf(lastNotional),
                false,
                Optional.empty());

        List<PendingClose> closesToApply = new ArrayList<>();
        List<PendingOpen> opensToApply = new ArrayList<>();
        Map<String, BigDecimal> desiredBySymbol = new HashMap<>();

        for (String symbol : warmContexts.keySet()) {
          TargetPosition tp = targetsBySymbol.get(symbol);
          BigDecimal desired =
              tp == null
                  ? BigDecimal.ZERO
                  : tp.targetWeight().multiply(allocatedCapital, IndicatorMath.MC);
          // `current` is still used below purely as informational text in the synthesised
          // signal's explanation -- the trigger decision compares against `lastDesired`, never
          // the RM-capped `current`. See lastDesired's field comment for why.
          BigDecimal current = lastNotional.getOrDefault(symbol, BigDecimal.ZERO);
          BigDecimal previousDesired = lastDesired.getOrDefault(symbol, BigDecimal.ZERO);
          BigDecimal delta = desired.subtract(previousDesired, IndicatorMath.MC).abs();
          if (delta.compareTo(noTradeBandNotional) <= 0) {
            noTradeBandSkips++;
            continue;
          }

          MarketContext symbolCtx = warmContexts.get(symbol);
          boolean hadCarry = carryPositions.containsKey(symbol);

          if (positions.containsKey(symbol) || hadCarry) {
            TechnicalSignal closeSignal =
                targetWeightSignal(symbol, Direction.SELL, desired, current, t);
            ExecutionDecision decision = rm.gate(closeSignal, startOfBarState, symbolCtx);
            if (decision instanceof ExecutionDecision.Execute) {
              closesToApply.add(new PendingClose(symbol, hadCarry));
            } else {
              rmRefusals++;
              ExecutionDecision.Refuse refuse = (ExecutionDecision.Refuse) decision;
              refusals.add(
                  new Refusal(
                      refuse.asOf(),
                      symbol,
                      Direction.SELL,
                      refuse.reason(),
                      refuse.explanation()));
              // The old lot could not be closed -- do not attempt to open on top of it; retry
              // next bar, exactly like runTargets's single-leg path.
              continue;
            }
          }

          if (desired.signum() > 0) {
            boolean wantsCarry = tp != null && tp.shape() == PositionShape.DELTA_NEUTRAL_CARRY;
            TechnicalSignal openSignal =
                targetWeightSignal(symbol, Direction.BUY, desired, current, t);
            ExecutionDecision decision = rm.gate(openSignal, startOfBarState, symbolCtx);
            if (decision instanceof ExecutionDecision.Execute exec) {
              opensToApply.add(new PendingOpen(symbol, wantsCarry, exec));
              // Raw, pre-scale-down desired -- see the single-leg runTargets's identical comment.
              desiredBySymbol.put(symbol, desired);
            } else {
              rmRefusals++;
              ExecutionDecision.Refuse refuse = (ExecutionDecision.Refuse) decision;
              refusals.add(
                  new Refusal(
                      refuse.asOf(), symbol, Direction.BUY, refuse.reason(), refuse.explanation()));
            }
          }
        }

        // Apply closes first -- frees leverage headroom before the aggregate open check below.
        for (PendingClose pc : closesToApply) {
          if (pc.carry()) {
            PortfolioFundingArbHarnessV2.CloseOutcome outcome =
                PortfolioFundingArbHarnessV2.close(
                    cash,
                    carryPositions.get(pc.symbol()),
                    lastSpotCandle.get(pc.symbol()).close(),
                    lastCandle.get(pc.symbol()).close(),
                    singleLegAdverse,
                    t,
                    ExitReason.SIGNAL);
            cash = outcome.cash();
            tradesBySymbol.get(pc.symbol()).add(outcome.trade());
            totalFeesPaid = totalFeesPaid.add(outcome.feesPaid(), IndicatorMath.MC);
            carryPositions.remove(pc.symbol());
            lastNotional.remove(pc.symbol());
            lastDesired.remove(pc.symbol());
          } else {
            SymbolPosition pos = positions.get(pc.symbol());
            BigDecimal exitPrice = lastCandle.get(pc.symbol()).close();
            BigDecimal exitFee =
                pos.size()
                    .multiply(exitPrice, IndicatorMath.MC)
                    .multiply(singleLegAdverse, IndicatorMath.MC);
            totalFeesPaid = totalFeesPaid.add(exitFee, IndicatorMath.MC);
            cash =
                closePosition(
                    cash,
                    pos,
                    exitPrice,
                    singleLegAdverse,
                    t,
                    ExitReason.SIGNAL,
                    tradesBySymbol.get(pc.symbol()));
            positions.remove(pc.symbol());
            lastNotional.remove(pc.symbol());
            lastDesired.remove(pc.symbol());
          }
        }

        // Book-level proportional scale-down (same aggregate rule as runTargets): every open above
        // was individually approved against the SAME start-of-bar state, so their combined effect
        // was never checked together until now.
        if (!opensToApply.isEmpty()) {
          BigDecimal totalAfterCloses = computeEquity(BigDecimal.ZERO, lastNotional);
          BigDecimal sumOfOpens = BigDecimal.ZERO;
          for (PendingOpen po : opensToApply) {
            sumOfOpens = sumOfOpens.add(po.exec().notional(), IndicatorMath.MC);
          }
          BigDecimal maxAllowed =
              equityNow.multiply(rm.riskParameters().maxLeverage(), IndicatorMath.MC);
          if (rm.riskParameters().maxTotalDeployedAbs().isPresent()) {
            maxAllowed = maxAllowed.min(rm.riskParameters().maxTotalDeployedAbs().get());
          }
          BigDecimal hypotheticalTotal = totalAfterCloses.add(sumOfOpens, IndicatorMath.MC);
          BigDecimal scaleFactor = BigDecimal.ONE;
          if (hypotheticalTotal.compareTo(maxAllowed) > 0) {
            BigDecimal availableRoom = maxAllowed.subtract(totalAfterCloses, IndicatorMath.MC);
            scaleFactor =
                availableRoom.signum() <= 0
                    ? BigDecimal.ZERO
                    : availableRoom.divide(sumOfOpens, IndicatorMath.MC).min(BigDecimal.ONE);
            bookScaleDowns++;
          }

          for (PendingOpen po : opensToApply) {
            BigDecimal scaledNotional =
                po.exec().notional().multiply(scaleFactor, IndicatorMath.MC);
            if (scaledNotional.signum() <= 0) {
              refusals.add(
                  new Refusal(
                      t,
                      po.symbol(),
                      Direction.BUY,
                      ExecutionDecision.RefusalReason.LEVERAGE_CAP_EXCEEDED,
                      "book-level proportional scale-down reduced this open to zero (aggregate"
                          + " open notional would have exceeded the leverage/absolute cap)"));
              continue;
            }
            if (po.carry()) {
              Candle spot = lastSpotCandle.get(po.symbol());
              Candle perp = lastCandle.get(po.symbol());
              BigDecimal q = scaledNotional.divide(spot.close(), IndicatorMath.MC);
              BigDecimal spotCost = q.multiply(spot.close(), IndicatorMath.MC);
              BigDecimal spotEntryFee = spotCost.multiply(singleLegAdverse, IndicatorMath.MC);
              BigDecimal perpEntryFee =
                  q.multiply(perp.close(), IndicatorMath.MC)
                      .multiply(singleLegAdverse, IndicatorMath.MC);
              BigDecimal margin =
                  q.multiply(perp.close(), IndicatorMath.MC).divide(leverage, IndicatorMath.MC);
              BigDecimal cashBeforeEntry = cash;
              cash = cash.subtract(spotCost).subtract(spotEntryFee).subtract(perpEntryFee);
              carryPositions.put(
                  po.symbol(),
                  new PortfolioFundingArbHarnessV2.OpenPosition(
                      q, spot.close(), perp.close(), margin, cashBeforeEntry, t, 0));
              lastNotional.put(po.symbol(), q.multiply(perp.close(), IndicatorMath.MC));
              lastDesired.put(po.symbol(), desiredBySymbol.get(po.symbol()));
              totalFeesPaid =
                  totalFeesPaid.add(
                      spotEntryFee.add(perpEntryFee, IndicatorMath.MC), IndicatorMath.MC);
            } else {
              BigDecimal scaledSize = po.exec().size().multiply(scaleFactor, IndicatorMath.MC);
              BigDecimal entryFee = scaledNotional.multiply(singleLegAdverse, IndicatorMath.MC);
              BigDecimal cashBeforeEntry = cash;
              cash =
                  cash.subtract(scaledNotional, IndicatorMath.MC)
                      .subtract(entryFee, IndicatorMath.MC);
              Candle candle = lastCandle.get(po.symbol());
              positions.put(
                  po.symbol(), new SymbolPosition(scaledSize, candle.close(), t, cashBeforeEntry));
              lastNotional.put(po.symbol(), scaledNotional);
              lastDesired.put(po.symbol(), desiredBySymbol.get(po.symbol()));
              totalFeesPaid = totalFeesPaid.add(entryFee, IndicatorMath.MC);
            }
          }
        }
      }

      BigDecimal equity =
          computeBookEquity(cash, positions, carryPositions, lastCandle, lastSpotCandle);
      equityCurve.add(new EquityPoint(t, equity));
      if (!cbTripped) {
        if (equity.compareTo(peakEquity) > 0) {
          peakEquity = equity;
        }
        if (CircuitBreaker.shouldTrip(
            equity, peakEquity, rm.riskParameters().maxPortfolioDrawdownPct())) {
          cbTripped = true;
        }
      }
    }

    // End-of-data sweep: drain remaining funding, then close whatever is still open, at each
    // symbol's own last candle(s).
    for (String symbol : symbols) {
      List<FundingEvent> fundingList = fundingBySymbol.getOrDefault(symbol, List.of());
      int fIdx = fundingIdx.get(symbol);
      Candle lp = lastCandle.get(symbol);
      while (fIdx < fundingList.size()) {
        FundingEvent fe = fundingList.get(fIdx++);
        PortfolioFundingArbHarnessV2.OpenPosition pos = carryPositions.get(symbol);
        if (pos != null && lp != null) {
          cash =
              cash.add(
                  pos.q()
                      .multiply(fe.rate(), IndicatorMath.MC)
                      .multiply(lp.close(), IndicatorMath.MC));
        }
      }

      SymbolPosition singlePos = positions.get(symbol);
      if (singlePos != null && lp != null) {
        BigDecimal exitFee =
            singlePos
                .size()
                .multiply(lp.close(), IndicatorMath.MC)
                .multiply(singleLegAdverse, IndicatorMath.MC);
        totalFeesPaid = totalFeesPaid.add(exitFee, IndicatorMath.MC);
        cash =
            closePosition(
                cash,
                singlePos,
                lp.close(),
                singleLegAdverse,
                lp.openTime(),
                ExitReason.END_OF_DATA,
                tradesBySymbol.get(symbol));
      }

      PortfolioFundingArbHarnessV2.OpenPosition carryPos = carryPositions.get(symbol);
      Candle ls = lastSpotCandle.get(symbol);
      if (carryPos != null && lp != null && ls != null) {
        PortfolioFundingArbHarnessV2.CloseOutcome outcome =
            PortfolioFundingArbHarnessV2.close(
                cash,
                carryPos,
                ls.close(),
                lp.close(),
                singleLegAdverse,
                lp.openTime(),
                ExitReason.END_OF_DATA);
        cash = outcome.cash();
        tradesBySymbol.get(symbol).add(outcome.trade());
        totalFeesPaid = totalFeesPaid.add(outcome.feesPaid(), IndicatorMath.MC);
      }
    }

    List<Trade> allTrades = new ArrayList<>();
    for (List<Trade> ts : tradesBySymbol.values()) {
      allTrades.addAll(ts);
    }
    allTrades.sort((a, b) -> a.entryTime().compareTo(b.entryTime()));

    BigDecimal endingEquity = cash;
    BigDecimal totalReturn =
        cfg.startingCash().signum() == 0
            ? BigDecimal.ZERO
            : endingEquity
                .subtract(cfg.startingCash())
                .divide(cfg.startingCash(), IndicatorMath.MC);
    double sharpe = Metrics.annualisedSharpe(equityCurve);
    BigDecimal maxDd = Metrics.maxDrawdown(equityCurve);
    double winRate = Metrics.winRate(allTrades);

    Map<String, Object> diag = new HashMap<>();
    diag.put("evaluations", evaluations);
    diag.put("bars", (long) equityCurve.size());
    diag.put("rmRefusals", rmRefusals);
    diag.put("rmGated", true);
    diag.put("harness", "portfolio-targets-carry");
    diag.put("symbols", symbols.size());
    diag.put("bookScaleDowns", bookScaleDowns);
    diag.put("noTradeBandSkips", noTradeBandSkips);
    diag.put("allocatedCapital", allocatedCapital);
    diag.put("noTradeBand", noTradeBand);
    diag.put("liquidations", liquidations);
    diag.put("liquidationEvents", List.copyOf(liquidationEvents));
    diag.put("totalFeesPaid", totalFeesPaid);
    for (String symbol : symbols) {
      diag.put("barsSkipped." + symbol, barsSkipped.get(symbol));
      diag.put("trades." + symbol, (long) tradesBySymbol.get(symbol).size());
      BigDecimal symbolPnl = BigDecimal.ZERO;
      for (Trade tr : tradesBySymbol.get(symbol)) {
        symbolPnl = symbolPnl.add(tr.pnl(), IndicatorMath.MC);
      }
      diag.put("pnl." + symbol, symbolPnl);
    }

    return new BacktestResult(
        cfg.startingCash(),
        endingEquity,
        totalReturn,
        sharpe,
        maxDd,
        winRate,
        allTrades.size(),
        allTrades,
        equityCurve,
        diag,
        refusals);
  }

  private static BigDecimal computeEquity(
      BigDecimal cash, Map<String, BigDecimal> notionalBySymbol) {
    BigDecimal equity = cash;
    for (BigDecimal notional : notionalBySymbol.values()) {
      equity = equity.add(notional, IndicatorMath.MC);
    }
    return equity;
  }

  /**
   * Synthesises the {@link TechnicalSignal} {@link #runTargets} feeds {@link
   * app.viglide.core.risk.RiskManager#gate} for one rebalance leg (a close or a reopen). Confidence
   * is always {@code 1.0} — see {@link #runTargets}'s Javadoc for why target-weight magnitude must
   * not be reused as a confidence score.
   */
  private static TechnicalSignal targetWeightSignal(
      String symbol, Direction direction, BigDecimal desired, BigDecimal current, Instant asOf) {
    return new TechnicalSignal(
        symbol,
        direction,
        1.0,
        List.of(
            new Factor(
                "TARGET_WEIGHT",
                "desired=" + desired.toPlainString() + " current=" + current.toPlainString(),
                1.0)),
        "runTargets rebalance ("
            + direction
            + "): desired="
            + desired.toPlainString()
            + ", current="
            + current.toPlainString(),
        asOf);
  }

  private record SymbolPosition(
      BigDecimal size, BigDecimal entryPrice, Instant entryTime, BigDecimal cashBeforeEntry) {}

  /**
   * A rebalance leg the two-leg {@link #runTargets} overload decided to close this bar, tagged by
   * which position map it belongs to — {@code carry == true} means {@link
   * PortfolioFundingArbHarnessV2#close}, {@code false} means this class's own {@link
   * #closePosition}. What is currently open determines this, independently of what the strategy's
   * new target wants (a symbol can legally close one shape and reopen the other in the same bar).
   */
  private record PendingClose(String symbol, boolean carry) {}

  /** A rebalance leg the two-leg {@link #runTargets} overload approved to open this bar. */
  private record PendingOpen(String symbol, boolean carry, ExecutionDecision.Execute exec) {}

  /**
   * Mark-to-market equity for the two-leg {@link #runTargets} overload's mixed book: cash plus
   * every open carry position's spot-value-plus-perp-unrealised (delegated to {@link
   * PortfolioFundingArbHarnessV2#computeEquity}, so the two harnesses cannot disagree on a carry
   * leg's worth) plus every open single-leg position's plain mark ({@code size × last close}).
   */
  private static BigDecimal computeBookEquity(
      BigDecimal cash,
      Map<String, SymbolPosition> positions,
      Map<String, PortfolioFundingArbHarnessV2.OpenPosition> carryPositions,
      Map<String, Candle> lastCandle,
      Map<String, Candle> lastSpotCandle) {
    BigDecimal equity =
        PortfolioFundingArbHarnessV2.computeEquity(
            cash, carryPositions, lastCandle, lastSpotCandle);
    for (Map.Entry<String, SymbolPosition> e : positions.entrySet()) {
      Candle mark = lastCandle.get(e.getKey());
      if (mark != null) {
        equity =
            equity.add(
                e.getValue().size().multiply(mark.close(), IndicatorMath.MC), IndicatorMath.MC);
      }
    }
    return equity;
  }

  private static BigDecimal closePosition(
      BigDecimal cash,
      SymbolPosition pos,
      BigDecimal exitPrice,
      BigDecimal adverseFactor,
      Instant exitTime,
      ExitReason reason,
      List<Trade> tradesOut) {
    BigDecimal proceeds = pos.size().multiply(exitPrice, IndicatorMath.MC);
    BigDecimal exitFee = proceeds.multiply(adverseFactor, IndicatorMath.MC);
    BigDecimal newCash = cash.add(proceeds, IndicatorMath.MC).subtract(exitFee, IndicatorMath.MC);
    tradesOut.add(
        new Trade(
            pos.entryTime(),
            exitTime,
            Direction.BUY,
            pos.entryPrice(),
            exitPrice,
            pos.size(),
            newCash.subtract(pos.cashBeforeEntry(), IndicatorMath.MC),
            reason));
    return newCash;
  }
}
