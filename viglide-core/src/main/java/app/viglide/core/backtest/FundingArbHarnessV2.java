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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Backtest harness for funding-rate arbitrage, <strong>two-leg</strong> model (PLAN-008 Task F,
 * ADR-0009). Unlike {@link FundingArbHarness} (v1, income-only — no spot leg, no basis P&amp;L, no
 * liquidation risk), this harness models the real trade: long spot + short perp, same quantity
 * {@code q}, with real fills on both legs, a funding accrual, and a margin liquidation guard on the
 * perp leg. v1 is kept for comparability; select via {@code --funding-model=v1|v2}.
 *
 * <p><strong>Modelling assumptions (all recorded in ADR-0009):</strong>
 *
 * <ul>
 *   <li><strong>Same-bar joins, close-price fills.</strong> Perp and spot candles are joined on
 *       {@code openTime}; a bar missing on either side is skipped entirely (counted in diagnostics,
 *       no equity point emitted for it). Entries, exits, and mark-to-market all use that bar's
 *       <em>close</em> — no next-bar-open delay, unlike {@link BacktestHarness}.
 *   <li><strong>Margin is collateral, not an expense.</strong> {@code margin = q × perpEntry /
 *       leverage} is computed at entry purely as the liquidation-guard threshold; it is never
 *       subtracted from {@code cash}. Only the four leg fees (spot entry, perp entry, spot exit,
 *       perp exit) touch cash beyond the funding accrual and the perp's realised P&amp;L — this is
 *       what makes the flat-price fixture test's arithmetic exact (see the harness test suite).
 *   <li><strong>Single-venue margin, no cross-margining.</strong> The spot leg cannot post margin
 *       for the perp short in a violent pump — the liquidation guard is the harness's
 *       acknowledgment that "economically hedged" is not the same thing as "margin-safe."
 *   <li><strong>Liquidation always costs taker fees</strong> ({@link FeeModel#taker()}), regardless
 *       of the configured {@code --fee-mode} — a forced close cannot be a resting limit order.
 *   <li><strong>Both legs are charged one rate, and it is the derivatives rate.</strong> {@code
 *       spotEntryFee} and {@code perpEntryFee} (and their exit counterparts) both apply {@code
 *       cfg.fees().totalAdverseFactor(...)}, so a spot fill is costed at the perp's 5 bps rather
 *       than a spot venue's own schedule. See {@link FeeModel} for the size of the resulting
 *       understatement and why it is documented rather than silently corrected.
 * </ul>
 */
public final class FundingArbHarnessV2 {

  /**
   * Liquidation triggers once the perp leg's unrealised loss reaches this fraction of the margin
   * posted at entry. Named per CLAUDE.md §5 (no magic numbers); 0.9, not 1.0, because the harness
   * only re-checks once per bar — a buffer avoids "liquidated one bar late, past zero margin."
   */
  public static final BigDecimal LIQUIDATION_MARGIN_BUFFER = new BigDecimal("0.9");

  /**
   * Fallback leverage when no Risk Manager is wired (matches {@code RiskParameters.defaults()}).
   */
  private static final BigDecimal DEFAULT_LEVERAGE = new BigDecimal("2.0");

  /**
   * Hard cap on the premium-index window handed to a strategy (PLAN-015 Task C) — 24h of 1-minute
   * samples, comfortably more than the 8h funding interval a nowcast needs. Named per CLAUDE.md §5.
   *
   * <p>Unlike {@code fundingWindow}, this one <em>must</em> be bounded: the window is deep-copied
   * into every evaluated bar's {@link MarketContext}, so letting it grow for the whole run makes
   * the harness quadratic in bar count. Funding gets away with it at 3 events/day; a 1-minute
   * premium series is ~175x denser, and an uncapped 1-year run costs ~2.3e9 element copies per
   * symbol. A strategy needing a longer lookback should pre-aggregate rather than raise this.
   */
  static final int PREMIUM_INDEX_WINDOW_MAX_SAMPLES = 1440;

  private FundingArbHarnessV2() {}

  /**
   * Runs the two-leg strategy over the joined (perp, spot, funding) timeline with optional Risk
   * Manager gating.
   *
   * @param perpCandles perpetual-future klines, ascending {@code openTime} — also what the strategy
   *     sees via {@code MarketContext.candles()} (matches v1; {@link
   *     app.viglide.strategies.fundingarb.FundingArbStrategy} reads only {@code fundingHistory()}).
   * @param spotCandles spot klines, ascending {@code openTime}; joined to {@code perpCandles} by
   *     exact {@code openTime} match.
   * @param rm optional Risk Manager; {@link Optional#empty()} for the legacy ungated path.
   * @param perpSubBarCandles PLAN-009 Task B2: optional finer-grained perp candle series (e.g. 1m
   *     bars), ascending {@code openTime}, sliced per perp decision bar to re-check the liquidation
   *     guard at each sub-bar's close instead of only the decision bar's close — the coarse model's
   *     real blind spot (an intra-bar spike-and-recover past the margin buffer is invisible if you
   *     only look at the hourly close; see the PLAN-009 Task B2 note on why H4's "not worse"
   *     expectation does not automatically apply here the way it does to SL/TP). Empty (the default
   *     via the overloads below) preserves the original close-only behaviour exactly. Deliberately
   *     uses each sub-bar's <em>close</em>, not its high, for both the trigger check and the fill
   *     price — the minimal extension of the existing close-only formula to finer granularity, not
   *     a new worst-case-wick assumption the plan didn't ask for here.
   */
  public static BacktestResult run(
      TradingStrategy strategy,
      List<Candle> perpCandles,
      List<Candle> spotCandles,
      List<FundingEvent> fundingEvents,
      List<PremiumIndexEvent> premiumIndexEvents,
      String symbol,
      CandleInterval interval,
      BacktestConfig cfg,
      Optional<RiskManagerPort> rm,
      List<Candle> perpSubBarCandles) {
    Objects.requireNonNull(strategy, "strategy");
    Objects.requireNonNull(perpCandles, "perpCandles");
    Objects.requireNonNull(spotCandles, "spotCandles");
    Objects.requireNonNull(fundingEvents, "fundingEvents");
    Objects.requireNonNull(premiumIndexEvents, "premiumIndexEvents");
    Objects.requireNonNull(symbol, "symbol");
    Objects.requireNonNull(interval, "interval");
    Objects.requireNonNull(cfg, "cfg");
    Objects.requireNonNull(rm, "rm");
    Objects.requireNonNull(perpSubBarCandles, "perpSubBarCandles");
    validatePremiumIndexSeries(premiumIndexEvents, interval, symbol);

    Map<Instant, Candle> spotByOpenTime = new HashMap<>(spotCandles.size() * 2);
    for (Candle c : spotCandles) {
      spotByOpenTime.put(c.openTime(), c);
    }

    BigDecimal singleLegAdverse = cfg.fees().totalAdverseFactor(IndicatorMath.MC);
    BigDecimal liquidationAdverse = FeeModel.taker().totalAdverseFactor(IndicatorMath.MC);
    BigDecimal leverage = rm.map(r -> r.riskParameters().maxLeverage()).orElse(DEFAULT_LEVERAGE);
    BigDecimal maxDrawdownPct =
        rm.map(r -> r.riskParameters().maxPortfolioDrawdownPct()).orElse(new BigDecimal("0.15"));

    BigDecimal cash = cfg.startingCash();
    OpenPosition position = null; // null = flat

    List<Trade> trades = new ArrayList<>();
    List<EquityPoint> equityCurve = new ArrayList<>();
    List<Refusal> refusals = new ArrayList<>();
    Deque<Candle> window = new ArrayDeque<>(cfg.warmupBars());
    Deque<FundingEvent> fundingWindow = new ArrayDeque<>();
    Deque<PremiumIndexEvent> premiumIndexWindow = new ArrayDeque<>();

    int fundingIdx = 0;
    int premiumIndexIdx = 0;
    int subBarIdx = 0;
    int barIndex = 0;
    long evaluations = 0;
    long signals = 0;
    long rmRefusals = 0;
    long barsSkippedNoSpotMatch = 0;
    long liquidations = 0;
    BigDecimal peakEquity = cfg.startingCash();
    boolean cbTripped = false;
    // PLAN-008 Task I: gross funding collected vs total fees paid, tracked separately from the
    // single `cash` balance so the K1 verdict's fee-bound hypothesis can be checked with real
    // numbers instead of backed out (imprecisely) from net PnL alone.
    BigDecimal netFundingAccrued = BigDecimal.ZERO;
    BigDecimal totalFeesPaid = BigDecimal.ZERO;

    Candle lastPerp = null;
    Candle lastSpot = null;

    for (Candle perp : perpCandles) {
      Candle spot = spotByOpenTime.get(perp.openTime());
      if (spot == null) {
        barsSkippedNoSpotMatch++;
        continue;
      }
      lastPerp = perp;
      lastSpot = spot;

      // 1. Funding accrual: drain events up to this bar's openTime. Short perp receives positive
      // funding (rate > 0 ⇒ cash inflow), using this bar's perp close as the mark.
      while (fundingIdx < fundingEvents.size()
          && !fundingEvents.get(fundingIdx).time().isAfter(perp.openTime())) {
        FundingEvent fe = fundingEvents.get(fundingIdx++);
        fundingWindow.addLast(fe);
        if (position != null) {
          BigDecimal funding =
              position
                  .q()
                  .multiply(fe.rate(), IndicatorMath.MC)
                  .multiply(perp.close(), IndicatorMath.MC);
          cash = cash.add(funding);
          netFundingAccrued = netFundingAccrued.add(funding);
        }
      }

      // 1a. Premium-index nowcast window: drain samples up to this bar's openTime, same
      // monotonic-cursor no-lookahead pattern as the funding drain above — this is windowing only,
      // it never touches cash (see MarketContext.premiumIndexHistory's Javadoc for why the two
      // channels must stay separate).
      while (premiumIndexIdx < premiumIndexEvents.size()
          && !premiumIndexEvents.get(premiumIndexIdx).time().isAfter(perp.openTime())) {
        premiumIndexWindow.addLast(premiumIndexEvents.get(premiumIndexIdx++));
      }
      // Evict from the head, never the tail: a nowcast reads the most recent samples, and an
      // unbounded window would be copied into every bar's MarketContext (see
      // PREMIUM_INDEX_WINDOW_MAX_SAMPLES).
      while (premiumIndexWindow.size() > PREMIUM_INDEX_WINDOW_MAX_SAMPLES) {
        premiumIndexWindow.pollFirst();
      }

      // 1b. Slice perp sub-bars (if any) covering this bar's window — same monotonic-cursor
      // pattern as the funding drain above, advanced every bar regardless of position state so it
      // never falls behind (PLAN-009 Task B2).
      List<Candle> subBarSlice = List.of();
      if (!perpSubBarCandles.isEmpty()) {
        Instant windowEnd = perp.openTime().plus(interval.duration());
        while (subBarIdx < perpSubBarCandles.size()
            && perpSubBarCandles.get(subBarIdx).openTime().isBefore(perp.openTime())) {
          subBarIdx++;
        }
        int sliceStart = subBarIdx;
        while (subBarIdx < perpSubBarCandles.size()
            && perpSubBarCandles.get(subBarIdx).openTime().isBefore(windowEnd)) {
          subBarIdx++;
        }
        subBarSlice = perpSubBarCandles.subList(sliceStart, subBarIdx);
        validatePerpSubBarsWithinParentRange(perp, subBarSlice, windowEnd);
      }

      // 2. Liquidation guard — checked every bar a position is open, before this bar's equity is
      // recorded, so the equity curve never shows a "fine" mark for a bar that already breached
      // the margin buffer. When sub-bars are available, each sub-bar's close is checked in order
      // (first breach wins) instead of only the decision bar's close.
      if (position != null) {
        List<Candle> liquidationChecks = subBarSlice.isEmpty() ? List.of(perp) : subBarSlice;
        for (Candle check : liquidationChecks) {
          if (position == null) break; // already liquidated by an earlier sub-bar this decision bar
          BigDecimal perpLoss =
              check
                  .close()
                  .subtract(position.perpEntry(), IndicatorMath.MC)
                  .multiply(position.q(), IndicatorMath.MC);
          if (perpLoss.signum() > 0
              && perpLoss.compareTo(
                      position.margin().multiply(LIQUIDATION_MARGIN_BUFFER, IndicatorMath.MC))
                  >= 0) {
            CloseOutcome outcome =
                close(
                    cash,
                    position,
                    spot.close(),
                    check.close(),
                    liquidationAdverse,
                    perp.openTime(),
                    ExitReason.LIQUIDATION_GUARD);
            cash = outcome.cash();
            trades.add(outcome.trade());
            totalFeesPaid = totalFeesPaid.add(outcome.feesPaid());
            liquidations++;
            position = null;
          }
        }
      }

      // 3. Mark-to-market and equity point.
      BigDecimal equity =
          position == null
              ? cash
              : cash.add(position.q().multiply(spot.close(), IndicatorMath.MC))
                  .add(
                      position
                          .perpEntry()
                          .subtract(perp.close(), IndicatorMath.MC)
                          .multiply(position.q(), IndicatorMath.MC));
      equityCurve.add(new EquityPoint(perp.openTime(), equity));

      // 4. Circuit breaker (RM path only) — configured fees, not forced taker; a policy decision,
      // not a margin emergency.
      if (rm.isPresent() && !cbTripped) {
        if (equity.compareTo(peakEquity) > 0) peakEquity = equity;
        if (CircuitBreaker.shouldTrip(equity, peakEquity, maxDrawdownPct)) {
          cbTripped = true;
          if (position != null) {
            CloseOutcome outcome =
                close(
                    cash,
                    position,
                    spot.close(),
                    perp.close(),
                    singleLegAdverse,
                    perp.openTime(),
                    ExitReason.STOP_LOSS);
            cash = outcome.cash();
            trades.add(outcome.trade());
            totalFeesPaid = totalFeesPaid.add(outcome.feesPaid());
            position = null;
          }
        }
      }

      // 5. Slide the perp window (what the strategy — and the RM's ATR fallback — sees).
      if (window.size() >= cfg.warmupBars()) {
        window.pollFirst();
      }
      window.addLast(perp);

      // 6. Evaluate strategy once the window is full and CB is not tripped.
      if (window.size() == cfg.warmupBars() && !cbTripped) {
        evaluations++;
        MarketContext ctx =
            new MarketContext(
                symbol,
                interval,
                new ArrayList<>(window),
                new ArrayList<>(fundingWindow),
                new ArrayList<>(premiumIndexWindow),
                Optional.empty(),
                cfg.exchangeFilters());
        Optional<TechnicalSignal> sig = strategy.evaluate(ctx);
        if (sig.isPresent()) {
          signals++;
          Direction d = sig.get().direction();

          if (d == Direction.BUY && position == null) {
            OpenAttempt attempt =
                open(
                    cash,
                    sig.get(),
                    ctx,
                    rm,
                    cfg,
                    spot.close(),
                    perp.close(),
                    leverage,
                    singleLegAdverse,
                    perp.openTime(),
                    barIndex,
                    peakEquity);
            if (attempt.refusal() != null) {
              rmRefusals++;
              refusals.add(attempt.refusal());
            } else {
              cash = attempt.cash();
              position = attempt.position();
              totalFeesPaid = totalFeesPaid.add(attempt.feesPaid());
            }
          } else if (d == Direction.SELL && position != null) {
            int barsHeld = barIndex - position.entryBarIndex();
            if (barsHeld >= cfg.minHoldBars()) {
              boolean approved = true;
              if (rm.isPresent()) {
                BigDecimal notional = position.q().multiply(perp.close(), IndicatorMath.MC);
                PortfolioState state =
                    new PortfolioState(
                        cash,
                        equity,
                        peakEquity.max(equity),
                        Map.of(symbol, notional),
                        cbTripped,
                        Optional.empty());
                ExecutionDecision decision = rm.get().gate(sig.get(), state, ctx);
                if (decision instanceof ExecutionDecision.Refuse refuse) {
                  approved = false;
                  rmRefusals++;
                  refusals.add(
                      new Refusal(
                          refuse.asOf(),
                          symbol,
                          sig.get().direction(),
                          refuse.reason(),
                          refuse.explanation()));
                }
              }
              if (approved) {
                CloseOutcome outcome =
                    close(
                        cash,
                        position,
                        spot.close(),
                        perp.close(),
                        singleLegAdverse,
                        perp.openTime(),
                        ExitReason.SIGNAL);
                cash = outcome.cash();
                trades.add(outcome.trade());
                totalFeesPaid = totalFeesPaid.add(outcome.feesPaid());
                position = null;
              }
            }
            // else: min-hold blocks the exit — harness-level suppression, not an RM refusal.
          }
        }
      }
      barIndex++;
    }

    // End-of-data sweep: drain remaining funding, then close any still-open position.
    if (lastPerp != null) {
      while (fundingIdx < fundingEvents.size()) {
        FundingEvent fe = fundingEvents.get(fundingIdx++);
        if (position != null) {
          BigDecimal funding =
              position
                  .q()
                  .multiply(fe.rate(), IndicatorMath.MC)
                  .multiply(lastPerp.close(), IndicatorMath.MC);
          cash = cash.add(funding);
          netFundingAccrued = netFundingAccrued.add(funding);
        }
      }
      if (position != null) {
        CloseOutcome outcome =
            close(
                cash,
                position,
                lastSpot.close(),
                lastPerp.close(),
                singleLegAdverse,
                lastPerp.openTime(),
                ExitReason.END_OF_DATA);
        cash = outcome.cash();
        trades.add(outcome.trade());
        totalFeesPaid = totalFeesPaid.add(outcome.feesPaid());
      }
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
    diag.put("premiumIndexEvents", (long) premiumIndexEvents.size());
    diag.put("barsSkippedNoSpotMatch", barsSkippedNoSpotMatch);
    diag.put("liquidations", liquidations);
    diag.put("model", "funding-arb-v2");
    diag.put("harness", "funding-arb-v2");
    diag.put("netFundingAccrued", netFundingAccrued);
    diag.put("totalFeesPaid", totalFeesPaid);
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

  /**
   * Backward-compatible overload predating {@link #run(TradingStrategy, List, List, List, List,
   * String, CandleInterval, BacktestConfig, Optional, List)}'s premium-index parameter (PLAN-015
   * Task C). Defaults it to empty — every caller that hasn't opted into the nowcast series keeps
   * seeing an identical, unaffected run.
   */
  public static BacktestResult run(
      TradingStrategy strategy,
      List<Candle> perpCandles,
      List<Candle> spotCandles,
      List<FundingEvent> fundingEvents,
      String symbol,
      CandleInterval interval,
      BacktestConfig cfg,
      Optional<RiskManagerPort> rm,
      List<Candle> perpSubBarCandles) {
    return run(
        strategy,
        perpCandles,
        spotCandles,
        fundingEvents,
        List.of(),
        symbol,
        interval,
        cfg,
        rm,
        perpSubBarCandles);
  }

  /** Overload with an RM but no perp sub-bars (original close-only liquidation-guard timing). */
  public static BacktestResult run(
      TradingStrategy strategy,
      List<Candle> perpCandles,
      List<Candle> spotCandles,
      List<FundingEvent> fundingEvents,
      String symbol,
      CandleInterval interval,
      BacktestConfig cfg,
      Optional<RiskManagerPort> rm) {
    return run(
        strategy, perpCandles, spotCandles, fundingEvents, symbol, interval, cfg, rm, List.of());
  }

  /** Backward-compatible overload that runs without a Risk Manager (legacy ungated path). */
  public static BacktestResult run(
      TradingStrategy strategy,
      List<Candle> perpCandles,
      List<Candle> spotCandles,
      List<FundingEvent> fundingEvents,
      String symbol,
      CandleInterval interval,
      BacktestConfig cfg) {
    return run(
        strategy,
        perpCandles,
        spotCandles,
        fundingEvents,
        symbol,
        interval,
        cfg,
        Optional.empty(),
        List.of());
  }

  /**
   * Data-integrity guard for the premium-index series (PLAN-015 Task C), checked once per run
   * rather than per bar. Two distinct defects, both of which would otherwise mis-backtest in
   * silence — fail loudly instead (CLAUDE.md §5):
   *
   * <ul>
   *   <li><b>Sampling coarser than the bars.</b> A premium-index row is stamped with its kline's
   *       <em>open</em> time but carries that kline's <em>close</em> value, so the value only
   *       becomes observable at {@code openTime + samplingInterval}. The window drain admits rows
   *       at or before the decision bar's {@code openTime}, and the strategy is shown that bar's
   *       own close — so the series is lookahead-free exactly while it is sampled at least as
   *       finely as the bars. Sample a 4h premium series against 1h bars and a strategy silently
   *       sees three hours into the future, with every no-lookahead test still green.
   *   <li><b>Out-of-order input.</b> The drain is a monotonic cursor, so the first inversion stops
   *       it advancing and every later sample is silently dropped — while {@code
   *       diag.premiumIndexEvents} still reports the full input size. {@link
   *       app.viglide.core.data.CsvPremiumIndexReader} is explicitly built to tolerate merged
   *       multi-month dumps, which makes mis-ordered concatenation a realistic input.
   * </ul>
   *
   * <p>Cadence is estimated as the <em>minimum</em> positive gap, not the first or the maximum:
   * real Binance dumps have holes, which inflate individual gaps, so the minimum is the only
   * estimator that does not false-positive on a gappy but correctly-sampled series.
   *
   * <p>Package-private: {@link PortfolioFundingArbHarnessV2} reuses this exact guard per symbol
   * rather than duplicating it.
   */
  static void validatePremiumIndexSeries(
      List<PremiumIndexEvent> events, CandleInterval interval, String symbol) {
    if (events.size() < 2) return;
    Duration minGap = null;
    for (int i = 1; i < events.size(); i++) {
      Duration gap = Duration.between(events.get(i - 1).time(), events.get(i).time());
      if (gap.isNegative()) {
        throw new IllegalArgumentException(
            "premium-index series for "
                + symbol
                + " is not in ascending time order at index "
                + i
                + " ("
                + events.get(i - 1).time()
                + " then "
                + events.get(i).time()
                + ") — the harness drains it with a monotonic cursor and would silently drop"
                + " every later sample");
      }
      if (gap.isZero()) continue;
      if (minGap == null || gap.compareTo(minGap) < 0) minGap = gap;
    }
    if (minGap != null && minGap.compareTo(interval.duration()) > 0) {
      throw new IllegalArgumentException(
          "premium-index series for "
              + symbol
              + " is sampled every "
              + minGap
              + ", coarser than the "
              + interval.duration()
              + " decision bar — each sample carries its kline's close value, which would only"
              + " become observable after the bar it is shown to (lookahead). Re-download"
              + " premium-index klines at "
              + interval
              + " or finer");
    }
  }

  /**
   * Data-integrity guard (PLAN-009 Task B2, extended PLAN-011 Task E / F5), mirroring {@code
   * VirtualExchange}'s equivalent: every sub-bar's <em>close</em> must lie within its parent perp
   * bar's {@code [low, high]} range (this harness only ever reads sub-bar close prices, never
   * high/low, so that is the full price invariant here — unlike the OHLCV path, which also
   * validates wicks); every sub-bar's {@code openTime} must fall in {@code [parent.openTime(),
   * windowEnd)} (the same half-open interval this method's caller slices with); and the series must
   * be strictly ascending by {@code openTime}. A violation of any of these means the sub-bar series
   * and the decision-bar series were not derived from the same source — fail loudly rather than let
   * a mismatched pair/period or a mis-sliced cursor silently mistime a liquidation (CLAUDE.md §5).
   *
   * <p>Package-private (PLAN-011 Task F): {@link PortfolioFundingArbHarnessV2} reuses this exact
   * guard for its own per-symbol sub-bar slices rather than duplicating it.
   */
  static void validatePerpSubBarsWithinParentRange(
      Candle parent, List<Candle> subBars, Instant windowEnd) {
    Instant previousOpenTime = null;
    for (Candle sub : subBars) {
      if (sub.close().compareTo(parent.high()) > 0 || sub.close().compareTo(parent.low()) < 0) {
        throw new IllegalStateException(
            "perp sub-bar close out of parent bar's range at "
                + sub.openTime()
                + ": parent=["
                + parent.low()
                + ","
                + parent.high()
                + "] sub close="
                + sub.close()
                + " (parent bar opened "
                + parent.openTime()
                + ")");
      }
      if (sub.openTime().isBefore(parent.openTime()) || !sub.openTime().isBefore(windowEnd)) {
        throw new IllegalStateException(
            "perp sub-bar does not lie within its parent bar's time window at "
                + sub.openTime()
                + ": parent window=["
                + parent.openTime()
                + ","
                + windowEnd
                + ")");
      }
      if (previousOpenTime != null && !sub.openTime().isAfter(previousOpenTime)) {
        throw new IllegalStateException(
            "perp sub-bars are not strictly ascending by openTime: "
                + previousOpenTime
                + " then "
                + sub.openTime());
      }
      previousOpenTime = sub.openTime();
    }
  }

  // ── position lifecycle ───────────────────────────────────────────────────────────────────────

  /** Open two-leg position state: quantity, both legs' entry prices, and the liquidation margin. */
  private record OpenPosition(
      BigDecimal q,
      BigDecimal spotEntry,
      BigDecimal perpEntry,
      BigDecimal margin,
      BigDecimal cashBeforeEntry,
      Instant entryTime,
      int entryBarIndex) {}

  private record OpenAttempt(
      BigDecimal cash, OpenPosition position, Refusal refusal, BigDecimal feesPaid) {}

  private record CloseOutcome(BigDecimal cash, Trade trade, BigDecimal feesPaid) {}

  /**
   * Opens the position: sizes {@code q} from the RM's {@code Execute.size()} (converted to a dollar
   * notional at the current perp close) when gated, else {@code cash × maxPositionPct} (matching
   * v1's ungated convention) — either way {@code q = notional / spotClose}. Pays the full spot
   * notional plus both legs' entry fees from cash; margin is computed but not deducted (see the
   * class Javadoc).
   */
  private static OpenAttempt open(
      BigDecimal cash,
      TechnicalSignal signal,
      MarketContext ctx,
      Optional<RiskManagerPort> rm,
      BacktestConfig cfg,
      BigDecimal spotClose,
      BigDecimal perpClose,
      BigDecimal leverage,
      BigDecimal singleLegAdverse,
      Instant openTime,
      int barIndex,
      BigDecimal peakEquity) {
    BigDecimal notional;
    if (rm.isPresent()) {
      PortfolioState state =
          new PortfolioState(cash, cash, peakEquity.max(cash), Map.of(), false, Optional.empty());
      ExecutionDecision decision = rm.get().gate(signal, state, ctx);
      if (decision instanceof ExecutionDecision.Refuse refuse) {
        return new OpenAttempt(
            cash,
            null,
            new Refusal(
                refuse.asOf(),
                signal.symbol(),
                signal.direction(),
                refuse.reason(),
                refuse.explanation()),
            BigDecimal.ZERO);
      }
      ExecutionDecision.Execute exec = (ExecutionDecision.Execute) decision;
      // PLAN-012 Task D (review finding F11): read the RM's own notional rather than re-deriving
      // it against perpClose -- previously these agreed only because the perp series happens to
      // be ctx.candles() in this harness (ADR-0009), which a two-leg live executor cannot assume.
      notional = exec.notional();
    } else {
      notional = cash.multiply(cfg.maxPositionPct(), IndicatorMath.MC);
    }

    BigDecimal q = notional.divide(spotClose, IndicatorMath.MC);
    BigDecimal spotCost = q.multiply(spotClose, IndicatorMath.MC);
    BigDecimal spotEntryFee = spotCost.multiply(singleLegAdverse, IndicatorMath.MC);
    BigDecimal perpEntryFee =
        q.multiply(perpClose, IndicatorMath.MC).multiply(singleLegAdverse, IndicatorMath.MC);
    BigDecimal margin = q.multiply(perpClose, IndicatorMath.MC).divide(leverage, IndicatorMath.MC);

    BigDecimal cashBeforeEntry = cash;
    BigDecimal newCash = cash.subtract(spotCost).subtract(spotEntryFee).subtract(perpEntryFee);
    OpenPosition position =
        new OpenPosition(q, spotClose, perpClose, margin, cashBeforeEntry, openTime, barIndex);
    return new OpenAttempt(
        newCash, position, null, spotEntryFee.add(perpEntryFee, IndicatorMath.MC));
  }

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
            Direction
                .BUY, // basis trade has no directional side; record BUY by convention (matches v1)
            position.spotEntry(),
            spotClose,
            q,
            newCash.subtract(position.cashBeforeEntry()),
            reason);
    return new CloseOutcome(newCash, trade, spotExitFee.add(perpExitFee, IndicatorMath.MC));
  }

  /** Same equity-delta win-rate convention as v1 (per-trade {@code pnl} already reflects it). */
  private static double equityBasedWinRate(List<Trade> trades, List<EquityPoint> equityCurve) {
    if (trades.isEmpty() || equityCurve.isEmpty()) return 0.0;
    long wins = trades.stream().filter(t -> t.pnl().signum() > 0).count();
    return (double) wins / trades.size();
  }
}
