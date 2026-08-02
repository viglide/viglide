package app.viglide.core.backtest;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.PremiumIndexEvent;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Multi-symbol, two-leg (spot + perp) portfolio backtest harness (PLAN-011 Task F, finding F6).
 * {@link PortfolioBacktestHarness} (PLAN-008 Task H) shares one cash balance and one {@link
 * RiskManagerPort} gate across symbols but only ever modelled full price exposure (v1, no spot leg)
 * at a fixed 1h cadence. {@link FundingArbHarnessV2} models the real delta-neutral trade (long spot
 * + short perp, margin, liquidation guard) but only for one symbol at a time — its own Risk Manager
 * gate call sees an empty {@code Map.of()} for every other symbol's exposure, so it cannot enforce
 * CLAUDE.md §7's leverage/drawdown caps at the scope they actually apply to. This class combines
 * both: every symbol's two-leg position shares one cash balance and is gated against the
 * <em>true</em> portfolio notional (every other symbol's currently-open exposure), at whatever
 * {@link CandleInterval} the caller supplies (H3 asks about 15m specifically) and with optional
 * per-symbol sub-bar realism for the liquidation guard (PLAN-009 Task B2, PLAN-011 Task E's
 * extended guard).
 *
 * <p>This is the "true shared-margin/RM-gated portfolio run" H3's original verdict (PLAN-009,
 * `docs/notes/2026-07-14-plan009-verdict.md`) said did not yet exist — the equal-weight average of
 * independently-run per-pair OOS results used there could not see one pair's position eating into
 * another's leverage headroom. This harness can.
 *
 * <p><strong>Modelling notes (read alongside {@link FundingArbHarnessV2}'s and {@link
 * PortfolioBacktestHarness}'s class Javadocs — both apply):</strong>
 *
 * <ul>
 *   <li><strong>Bar iteration:</strong> the ascending union of every symbol's perp {@code
 *       openTime}. A symbol missing its perp candle, or missing a spot candle at that same
 *       timestamp, is skipped for that bar (counted in diagnostics as {@code barsSkipped.<SYMBOL>})
 *       — mirrors both source harnesses' gap tolerance.
 *   <li><strong>Entry gating uses true portfolio notional:</strong> every {@code Execute} gate
 *       call's {@link PortfolioState#openPositions()} is every symbol's current perp-marked
 *       notional, not just the symbol being evaluated — the entire point of this class.
 *   <li><strong>Liquidation guard is per-symbol margin-based</strong> (unchanged from {@link
 *       FundingArbHarnessV2}: {@link FundingArbHarnessV2#LIQUIDATION_MARGIN_BUFFER} of the symbol's
 *       own margin, checked every bar that symbol has a position open, using its sub-bar series
 *       when supplied) — <strong>separate from</strong> the portfolio-wide circuit breaker below; a
 *       symbol can be individually liquidated without tripping the portfolio breaker.
 *   <li><strong>Circuit breaker is portfolio-scoped</strong> (mirrors {@link
 *       PortfolioBacktestHarness}): checked once per bar-time on aggregate equity: cash + every
 *       open position's mark-to-market (spot notional + perp unrealised P&amp;L). Once tripped,
 *       every open position is force-closed at its own next available bar's open (both legs), and
 *       the Risk Manager's own {@code PortfolioState.circuitBreakerTripped()} check refuses every
 *       subsequent signal — this harness does not special-case that part either.
 * </ul>
 */
public final class PortfolioFundingArbHarnessV2 {

  private PortfolioFundingArbHarnessV2() {}

  /**
   * @param perpCandlesBySymbol perp klines per symbol, ascending {@code openTime}
   * @param spotCandlesBySymbol spot klines per symbol, ascending {@code openTime}; joined to that
   *     symbol's perp candles by exact {@code openTime} match (a bar missing on either side is
   *     skipped for that symbol, not fatal)
   * @param fundingBySymbol funding events per symbol, ascending {@code time}; a symbol absent (or
   *     mapped to an empty list) simply never accrues funding
   * @param strategiesBySymbol the strategy to evaluate for each symbol (may differ per symbol)
   * @param perpSubBarCandlesBySymbol optional finer-grained perp series per symbol (PLAN-009 Task
   *     B2) — a symbol absent from this map preserves the original close-only liquidation timing
   *     for that symbol only; other symbols are unaffected
   * @param interval the decision cadence every symbol is evaluated at (H3: 1h vs 15m)
   * @param rm mandatory — CLAUDE.md §11 forbids an ungated order path, portfolio mode included
   */
  public static BacktestResult run(
      Map<String, List<Candle>> perpCandlesBySymbol,
      Map<String, List<Candle>> spotCandlesBySymbol,
      Map<String, List<FundingEvent>> fundingBySymbol,
      Map<String, List<PremiumIndexEvent>> premiumIndexBySymbol,
      Map<String, TradingStrategy> strategiesBySymbol,
      Map<String, List<Candle>> perpSubBarCandlesBySymbol,
      CandleInterval interval,
      BacktestConfig cfg,
      RiskManagerPort rm) {
    Objects.requireNonNull(perpCandlesBySymbol, "perpCandlesBySymbol");
    Objects.requireNonNull(spotCandlesBySymbol, "spotCandlesBySymbol");
    Objects.requireNonNull(fundingBySymbol, "fundingBySymbol");
    Objects.requireNonNull(premiumIndexBySymbol, "premiumIndexBySymbol");
    Objects.requireNonNull(strategiesBySymbol, "strategiesBySymbol");
    Objects.requireNonNull(perpSubBarCandlesBySymbol, "perpSubBarCandlesBySymbol");
    Objects.requireNonNull(interval, "interval");
    Objects.requireNonNull(cfg, "cfg");
    Objects.requireNonNull(rm, "rm");
    if (!strategiesBySymbol.keySet().containsAll(perpCandlesBySymbol.keySet())) {
      throw new IllegalArgumentException("every symbol in perpCandlesBySymbol needs a strategy");
    }

    Set<String> symbols = perpCandlesBySymbol.keySet();
    BigDecimal singleLegAdverse = cfg.fees().totalAdverseFactor(IndicatorMath.MC);
    BigDecimal liquidationAdverse = FeeModel.taker().totalAdverseFactor(IndicatorMath.MC);
    BigDecimal leverage = rm.riskParameters().maxLeverage();
    BigDecimal maxDrawdownPct = rm.riskParameters().maxPortfolioDrawdownPct();

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
    Map<String, Deque<PremiumIndexEvent>> premiumIndexWindows = new HashMap<>();
    Map<String, Integer> fundingIdx = new HashMap<>();
    Map<String, Integer> premiumIndexIdx = new HashMap<>();
    Map<String, Integer> subBarIdx = new HashMap<>();
    Map<String, OpenPosition> positions = new HashMap<>();
    Map<String, BigDecimal> lastNotional = new HashMap<>();
    Map<String, Candle> lastPerp = new HashMap<>();
    Map<String, Candle> lastSpot = new HashMap<>();
    Map<String, List<Trade>> tradesBySymbol = new HashMap<>();
    Map<String, Long> barsSkipped = new HashMap<>();
    Map<String, Integer> barIndexBySymbol = new HashMap<>();
    Map<String, BigDecimal> totalFeesPaidBySymbol = new HashMap<>();
    for (String symbol : symbols) {
      windows.put(symbol, new ArrayDeque<>());
      fundingWindows.put(symbol, new ArrayDeque<>());
      premiumIndexWindows.put(symbol, new ArrayDeque<>());
      fundingIdx.put(symbol, 0);
      premiumIndexIdx.put(symbol, 0);
      subBarIdx.put(symbol, 0);
      tradesBySymbol.put(symbol, new ArrayList<>());
      barsSkipped.put(symbol, 0L);
      barIndexBySymbol.put(symbol, 0);
      totalFeesPaidBySymbol.put(symbol, BigDecimal.ZERO);
    }

    BigDecimal cash = cfg.startingCash();
    BigDecimal peakEquity = cfg.startingCash();
    boolean cbTripped = false;
    long evaluations = 0;
    long signals = 0;
    long rmRefusals = 0;
    long liquidations = 0;
    List<Refusal> refusals = new ArrayList<>();
    List<EquityPoint> equityCurve = new ArrayList<>();

    for (Instant t : allTimes) {
      for (String symbol : symbols) {
        Candle perp = perpBySymbolTime.get(symbol).get(t);
        Candle spot = perp == null ? null : spotBySymbolTime.get(symbol).get(t);
        if (perp == null || spot == null) {
          barsSkipped.merge(symbol, 1L, Long::sum);
          continue;
        }
        lastPerp.put(symbol, perp);
        lastSpot.put(symbol, spot);

        // Circuit breaker sweep: force-close this symbol at its first available bar since the
        // trip, both legs at that bar's open (mirrors PortfolioBacktestHarness's deferred sweep).
        if (cbTripped && positions.containsKey(symbol)) {
          CloseOutcome outcome =
              close(
                  cash,
                  positions.get(symbol),
                  spot.open(),
                  perp.open(),
                  liquidationAdverse,
                  t,
                  ExitReason.STOP_LOSS);
          cash = outcome.cash();
          tradesBySymbol.get(symbol).add(outcome.trade());
          totalFeesPaidBySymbol.merge(
              symbol, outcome.feesPaid(), (a, b) -> a.add(b, IndicatorMath.MC));
          positions.remove(symbol);
          lastNotional.remove(symbol);
        }

        // Funding accrual for a still-open position.
        List<FundingEvent> fundingList = fundingBySymbol.getOrDefault(symbol, List.of());
        int fIdx = fundingIdx.get(symbol);
        while (fIdx < fundingList.size()
            && !fundingList.get(fIdx).time().isAfter(perp.openTime())) {
          FundingEvent fe = fundingList.get(fIdx++);
          fundingWindows.get(symbol).addLast(fe);
          OpenPosition pos = positions.get(symbol);
          if (pos != null) {
            cash =
                cash.add(
                    pos.q()
                        .multiply(fe.rate(), IndicatorMath.MC)
                        .multiply(perp.close(), IndicatorMath.MC));
          }
        }
        fundingIdx.put(symbol, fIdx);

        // Premium-index nowcast window for this symbol: same monotonic-cursor no-lookahead drain
        // as the funding accrual above, windowing only — never touches cash (PLAN-015 Task C; see
        // MarketContext.premiumIndexHistory's Javadoc for why the two channels stay separate).
        List<PremiumIndexEvent> premiumIndexList =
            premiumIndexBySymbol.getOrDefault(symbol, List.of());
        int pIdx = premiumIndexIdx.get(symbol);
        while (pIdx < premiumIndexList.size()
            && !premiumIndexList.get(pIdx).time().isAfter(perp.openTime())) {
          premiumIndexWindows.get(symbol).addLast(premiumIndexList.get(pIdx++));
        }
        premiumIndexIdx.put(symbol, pIdx);

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

        // Liquidation guard — every bar this symbol has a position open, before this bar's equity
        // is recorded. When sub-bars are available, each sub-bar's close is checked in order
        // (first breach wins) instead of only the decision bar's close.
        OpenPosition pos = positions.get(symbol);
        if (pos != null) {
          List<Candle> liquidationChecks = subBarSlice.isEmpty() ? List.of(perp) : subBarSlice;
          for (Candle check : liquidationChecks) {
            pos = positions.get(symbol);
            if (pos == null) break; // already liquidated by an earlier sub-bar this decision bar
            BigDecimal perpLoss =
                check
                    .close()
                    .subtract(pos.perpEntry(), IndicatorMath.MC)
                    .multiply(pos.q(), IndicatorMath.MC);
            if (perpLoss.signum() > 0
                && perpLoss.compareTo(
                        pos.margin()
                            .multiply(
                                FundingArbHarnessV2.LIQUIDATION_MARGIN_BUFFER, IndicatorMath.MC))
                    >= 0) {
              CloseOutcome outcome =
                  close(
                      cash,
                      pos,
                      spot.close(),
                      check.close(),
                      liquidationAdverse,
                      perp.openTime(),
                      ExitReason.LIQUIDATION_GUARD);
              cash = outcome.cash();
              tradesBySymbol.get(symbol).add(outcome.trade());
              totalFeesPaidBySymbol.merge(
                  symbol, outcome.feesPaid(), (a, b) -> a.add(b, IndicatorMath.MC));
              liquidations++;
              positions.remove(symbol);
              lastNotional.remove(symbol);
            }
          }
        }

        // Slide this symbol's decision window.
        Deque<Candle> window = windows.get(symbol);
        if (window.size() >= cfg.warmupBars()) {
          window.pollFirst();
        }
        window.addLast(perp);

        // Refresh this symbol's mark for cross-symbol leverage/exposure snapshots.
        OpenPosition refreshed = positions.get(symbol);
        if (refreshed != null) {
          lastNotional.put(symbol, refreshed.q().multiply(perp.close(), IndicatorMath.MC));
        }

        if (window.size() < cfg.warmupBars() || cbTripped) {
          barIndexBySymbol.merge(symbol, 1, Integer::sum);
          continue;
        }

        evaluations++;
        MarketContext ctx =
            new MarketContext(
                symbol,
                interval,
                new ArrayList<>(window),
                new ArrayList<>(fundingWindows.get(symbol)),
                new ArrayList<>(premiumIndexWindows.get(symbol)),
                Optional.empty(),
                Optional.empty());
        var sig = strategiesBySymbol.get(symbol).evaluate(ctx);
        if (sig.isEmpty()) {
          barIndexBySymbol.merge(symbol, 1, Integer::sum);
          continue;
        }
        signals++;
        TechnicalSignal signal = sig.get();
        if (signal.direction() == Direction.HOLD) {
          barIndexBySymbol.merge(symbol, 1, Integer::sum);
          continue;
        }

        BigDecimal equityNow = computeEquity(cash, positions, lastPerp, lastSpot);
        int barIndex = barIndexBySymbol.get(symbol);

        if (signal.direction() == Direction.BUY && !positions.containsKey(symbol)) {
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
            // PLAN-012 Task D (review finding F11): read the RM's own notional rather than
            // re-deriving it against perp.close() -- see FundingArbHarnessV2's identical fix.
            BigDecimal notional = exec.notional();
            BigDecimal q = notional.divide(spot.close(), IndicatorMath.MC);
            BigDecimal spotCost = q.multiply(spot.close(), IndicatorMath.MC);
            BigDecimal spotEntryFee = spotCost.multiply(singleLegAdverse, IndicatorMath.MC);
            BigDecimal perpEntryFee =
                q.multiply(perp.close(), IndicatorMath.MC)
                    .multiply(singleLegAdverse, IndicatorMath.MC);
            BigDecimal margin =
                q.multiply(perp.close(), IndicatorMath.MC).divide(leverage, IndicatorMath.MC);
            BigDecimal cashBeforeEntry = cash;
            cash = cash.subtract(spotCost).subtract(spotEntryFee).subtract(perpEntryFee);
            positions.put(
                symbol,
                new OpenPosition(
                    q, spot.close(), perp.close(), margin, cashBeforeEntry, t, barIndex));
            lastNotional.put(symbol, q.multiply(perp.close(), IndicatorMath.MC));
            totalFeesPaidBySymbol.merge(
                symbol,
                spotEntryFee.add(perpEntryFee, IndicatorMath.MC),
                (a, b) -> a.add(b, IndicatorMath.MC));
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
        } else if (signal.direction() == Direction.SELL && positions.containsKey(symbol)) {
          OpenPosition openPos = positions.get(symbol);
          int barsHeld = barIndex - openPos.entryBarIndex();
          if (barsHeld >= cfg.minHoldBars()) {
            BigDecimal notional = openPos.q().multiply(perp.close(), IndicatorMath.MC);
            Map<String, BigDecimal> notionalForGate = new HashMap<>(lastNotional);
            notionalForGate.put(symbol, notional);
            PortfolioState state =
                new PortfolioState(
                    cash,
                    equityNow,
                    peakEquity.max(equityNow),
                    Map.copyOf(notionalForGate),
                    cbTripped,
                    Optional.empty());
            ExecutionDecision decision = rm.gate(signal, state, ctx);
            if (decision instanceof ExecutionDecision.Refuse refuse) {
              rmRefusals++;
              refusals.add(
                  new Refusal(
                      refuse.asOf(),
                      symbol,
                      signal.direction(),
                      refuse.reason(),
                      refuse.explanation()));
            } else {
              CloseOutcome outcome =
                  close(
                      cash,
                      openPos,
                      spot.close(),
                      perp.close(),
                      singleLegAdverse,
                      t,
                      ExitReason.SIGNAL);
              cash = outcome.cash();
              tradesBySymbol.get(symbol).add(outcome.trade());
              totalFeesPaidBySymbol.merge(
                  symbol, outcome.feesPaid(), (a, b) -> a.add(b, IndicatorMath.MC));
              positions.remove(symbol);
              lastNotional.remove(symbol);
            }
          }
          // else: min-hold blocks the exit — harness-level suppression, not an RM refusal.
        }
        barIndexBySymbol.merge(symbol, 1, Integer::sum);
      }

      BigDecimal equity = computeEquity(cash, positions, lastPerp, lastSpot);
      equityCurve.add(new EquityPoint(t, equity));
      if (!cbTripped) {
        if (equity.compareTo(peakEquity) > 0) {
          peakEquity = equity;
        }
        if (CircuitBreaker.shouldTrip(equity, peakEquity, maxDrawdownPct)) {
          cbTripped = true;
        }
      }
    }

    // End-of-data sweep: drain remaining funding, then close whatever is still open, at each
    // symbol's own last candle.
    for (String symbol : symbols) {
      List<FundingEvent> fundingList = fundingBySymbol.getOrDefault(symbol, List.of());
      int fIdx = fundingIdx.get(symbol);
      Candle lp = lastPerp.get(symbol);
      while (fIdx < fundingList.size()) {
        FundingEvent fe = fundingList.get(fIdx++);
        OpenPosition pos = positions.get(symbol);
        if (pos != null && lp != null) {
          cash =
              cash.add(
                  pos.q()
                      .multiply(fe.rate(), IndicatorMath.MC)
                      .multiply(lp.close(), IndicatorMath.MC));
        }
      }
      OpenPosition pos = positions.get(symbol);
      Candle ls = lastSpot.get(symbol);
      if (pos != null && lp != null && ls != null) {
        CloseOutcome outcome =
            close(
                cash,
                pos,
                ls.close(),
                lp.close(),
                singleLegAdverse,
                lp.openTime(),
                ExitReason.END_OF_DATA);
        cash = outcome.cash();
        tradesBySymbol.get(symbol).add(outcome.trade());
        totalFeesPaidBySymbol.merge(
            symbol, outcome.feesPaid(), (a, b) -> a.add(b, IndicatorMath.MC));
      }
    }

    List<Trade> allTrades = new ArrayList<>();
    BigDecimal totalFeesPaid = BigDecimal.ZERO;
    for (String symbol : symbols) {
      allTrades.addAll(tradesBySymbol.get(symbol));
      totalFeesPaid = totalFeesPaid.add(totalFeesPaidBySymbol.get(symbol), IndicatorMath.MC);
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
    double winRate = equityBasedWinRate(allTrades);

    Map<String, Object> diag = new HashMap<>();
    diag.put("evaluations", evaluations);
    diag.put("signals", signals);
    diag.put("bars", (long) equityCurve.size());
    diag.put("rmRefusals", rmRefusals);
    diag.put("rmGated", true);
    diag.put("harness", "portfolio-funding-arb-v2");
    diag.put("model", "funding-arb-v2");
    diag.put("symbols", symbols.size());
    diag.put("liquidations", liquidations);
    diag.put("totalFeesPaid", totalFeesPaid);
    long totalPremiumIndexEvents = 0;
    for (String symbol : symbols) {
      diag.put("barsSkipped." + symbol, barsSkipped.get(symbol));
      diag.put("trades." + symbol, (long) tradesBySymbol.get(symbol).size());
      totalPremiumIndexEvents += premiumIndexBySymbol.getOrDefault(symbol, List.of()).size();
    }
    diag.put("premiumIndexEvents", totalPremiumIndexEvents);

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
   * Backward-compatible overload predating this class's premium-index parameter (PLAN-015 Task C).
   * Defaults it to empty for every symbol — a caller that hasn't opted into the nowcast series
   * keeps seeing an identical, unaffected run.
   */
  public static BacktestResult run(
      Map<String, List<Candle>> perpCandlesBySymbol,
      Map<String, List<Candle>> spotCandlesBySymbol,
      Map<String, List<FundingEvent>> fundingBySymbol,
      Map<String, TradingStrategy> strategiesBySymbol,
      Map<String, List<Candle>> perpSubBarCandlesBySymbol,
      CandleInterval interval,
      BacktestConfig cfg,
      RiskManagerPort rm) {
    return run(
        perpCandlesBySymbol,
        spotCandlesBySymbol,
        fundingBySymbol,
        Map.of(),
        strategiesBySymbol,
        perpSubBarCandlesBySymbol,
        interval,
        cfg,
        rm);
  }

  /** Overload without sub-bar realism or premium-index data (original close-only timing). */
  public static BacktestResult run(
      Map<String, List<Candle>> perpCandlesBySymbol,
      Map<String, List<Candle>> spotCandlesBySymbol,
      Map<String, List<FundingEvent>> fundingBySymbol,
      Map<String, TradingStrategy> strategiesBySymbol,
      CandleInterval interval,
      BacktestConfig cfg,
      RiskManagerPort rm) {
    return run(
        perpCandlesBySymbol,
        spotCandlesBySymbol,
        fundingBySymbol,
        strategiesBySymbol,
        Map.of(),
        interval,
        cfg,
        rm);
  }

  private static BigDecimal computeEquity(
      BigDecimal cash,
      Map<String, OpenPosition> positions,
      Map<String, Candle> lastPerp,
      Map<String, Candle> lastSpot) {
    BigDecimal equity = cash;
    for (Map.Entry<String, OpenPosition> e : positions.entrySet()) {
      String symbol = e.getKey();
      OpenPosition pos = e.getValue();
      Candle perp = lastPerp.get(symbol);
      Candle spot = lastSpot.get(symbol);
      if (perp == null || spot == null) {
        continue; // no mark seen yet for this symbol (shouldn't happen once a position exists)
      }
      // Spot leg's current value (q * mark) plus the perp leg's unrealised P&L (short perp: entry
      // - mark, positive when price fell) -- mirrors FundingArbHarnessV2's single-symbol equity.
      BigDecimal spotValue = pos.q().multiply(spot.close(), IndicatorMath.MC);
      BigDecimal perpUnrealised =
          pos.perpEntry()
              .subtract(perp.close(), IndicatorMath.MC)
              .multiply(pos.q(), IndicatorMath.MC);
      equity = equity.add(spotValue, IndicatorMath.MC).add(perpUnrealised, IndicatorMath.MC);
    }
    return equity;
  }

  private static double equityBasedWinRate(List<Trade> trades) {
    if (trades.isEmpty()) return 0.0;
    long wins = trades.stream().filter(t -> t.pnl().signum() > 0).count();
    return (double) wins / trades.size();
  }

  /** Open two-leg position state: quantity, both legs' entry prices, and the liquidation margin. */
  private record OpenPosition(
      BigDecimal q,
      BigDecimal spotEntry,
      BigDecimal perpEntry,
      BigDecimal margin,
      BigDecimal cashBeforeEntry,
      Instant entryTime,
      int entryBarIndex) {}

  private record CloseOutcome(BigDecimal cash, Trade trade, BigDecimal feesPaid) {}

  /**
   * Closes the position, realising the spot sale, the perp buy-back P&amp;L, and both exit fees.
   */
  private static CloseOutcome close(
      BigDecimal cash,
      OpenPosition position,
      BigDecimal spotClose,
      BigDecimal perpClose,
      BigDecimal adverseFactor,
      Instant exitTime,
      ExitReason reason) {
    BigDecimal q = position.q();
    BigDecimal spotProceeds = q.multiply(spotClose, IndicatorMath.MC);
    BigDecimal spotExitFee = spotProceeds.multiply(adverseFactor, IndicatorMath.MC);
    BigDecimal perpPnl =
        position.perpEntry().subtract(perpClose, IndicatorMath.MC).multiply(q, IndicatorMath.MC);
    BigDecimal perpExitFee =
        q.multiply(perpClose, IndicatorMath.MC).multiply(adverseFactor, IndicatorMath.MC);

    BigDecimal newCash =
        cash.add(spotProceeds).subtract(spotExitFee).add(perpPnl).subtract(perpExitFee);
    Trade trade =
        new Trade(
            position.entryTime(),
            exitTime,
            Direction.BUY, // basis trade has no directional side; record BUY by convention (v1/v2)
            position.spotEntry(),
            spotClose,
            q,
            newCash.subtract(position.cashBeforeEntry()),
            reason);
    return new CloseOutcome(newCash, trade, spotExitFee.add(perpExitFee, IndicatorMath.MC));
  }
}
