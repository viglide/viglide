package app.viglide.research.calibrate;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.BacktestHarness;
import app.viglide.core.backtest.BacktestResult;
import app.viglide.core.backtest.FundingArbHarness;
import app.viglide.core.backtest.FundingArbHarnessV2;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.spi.TradingStrategy;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * How a calibration fold gets evaluated. The default OHLCV runner calls {@link BacktestHarness};
 * the funding-aware runner slices the global funding-event list to each fold's time range and
 * routes through {@link FundingArbHarness}. Pluggable so {@link CalibrationHarness} stays
 * strategy-agnostic.
 */
@FunctionalInterface
public interface FoldRunner {

  BacktestResult run(
      TradingStrategy strategy,
      List<Candle> chunk,
      String symbol,
      CandleInterval interval,
      BacktestConfig cfg);

  /** Default: OHLCV-only path through {@link BacktestHarness}. */
  static FoldRunner defaultOhlcv() {
    return (s, c, sym, iv, cfg) -> BacktestHarness.run(s, c.stream(), sym, iv, cfg);
  }

  /**
   * OHLCV path with sub-bar execution realism (PLAN-009 Task C): {@code allSubBars} (e.g. 1m
   * candles covering the whole calibration dataset) is sliced to each fold's own time range before
   * delegating to {@link BacktestHarness}, so SL/TP resolution during calibration uses the same
   * finer-grained ordering the OOS backtest does — otherwise a coarse interval's calibration would
   * be scored under the pessimistic worst-case rule while its OOS run isn't, an apples-to- oranges
   * comparison across the interval study's cells.
   */
  static FoldRunner defaultOhlcv(List<Candle> allSubBars) {
    Objects.requireNonNull(allSubBars, "allSubBars");
    return (s, chunk, sym, iv, cfg) -> {
      List<Candle> chunkSubBars = sliceSubBarsToRange(allSubBars, chunk, iv);
      return BacktestHarness.run(
          s, chunk.stream(), sym, iv, cfg, List.of(), Optional.empty(), chunkSubBars);
    };
  }

  /**
   * Funding-aware runner. Slices {@code allFunding} to each fold's [first, last] candle time range
   * before delegating to {@link FundingArbHarness}.
   */
  static FoldRunner withFunding(List<FundingEvent> allFunding) {
    Objects.requireNonNull(allFunding, "allFunding");
    return (s, chunk, sym, iv, cfg) -> {
      if (chunk.isEmpty()) {
        return BacktestHarness.run(s, chunk.stream(), sym, iv, cfg);
      }
      List<FundingEvent> chunkFunding = sliceFundingToRange(allFunding, chunk);
      return FundingArbHarness.run(s, chunk, chunkFunding, sym, iv, cfg);
    };
  }

  /**
   * Two-leg funding-aware runner (PLAN-008 Task F). Slices both {@code allFunding} and {@code
   * allSpotCandles} to each fold's [first, last] perp-candle time range before delegating to {@link
   * FundingArbHarnessV2}.
   */
  static FoldRunner withFundingV2(List<FundingEvent> allFunding, List<Candle> allSpotCandles) {
    Objects.requireNonNull(allFunding, "allFunding");
    Objects.requireNonNull(allSpotCandles, "allSpotCandles");
    return (s, chunk, sym, iv, cfg) -> {
      if (chunk.isEmpty()) {
        return BacktestHarness.run(s, chunk.stream(), sym, iv, cfg);
      }
      List<FundingEvent> chunkFunding = sliceFundingToRange(allFunding, chunk);
      List<Candle> chunkSpot = sliceToRange(allSpotCandles, chunk);
      return FundingArbHarnessV2.run(s, chunk, chunkSpot, chunkFunding, sym, iv, cfg);
    };
  }

  /**
   * Two-leg funding-aware runner with sub-bar liquidation-guard realism (PLAN-009 Task C): as
   * {@link #withFundingV2(List, List)}, plus {@code allPerpSubBars} (e.g. 1m perp candles) sliced
   * to each fold's range and passed through to {@link FundingArbHarnessV2}'s sub-bar liquidation
   * check.
   */
  static FoldRunner withFundingV2(
      List<FundingEvent> allFunding, List<Candle> allSpotCandles, List<Candle> allPerpSubBars) {
    Objects.requireNonNull(allFunding, "allFunding");
    Objects.requireNonNull(allSpotCandles, "allSpotCandles");
    Objects.requireNonNull(allPerpSubBars, "allPerpSubBars");
    return (s, chunk, sym, iv, cfg) -> {
      if (chunk.isEmpty()) {
        return BacktestHarness.run(s, chunk.stream(), sym, iv, cfg);
      }
      List<FundingEvent> chunkFunding = sliceFundingToRange(allFunding, chunk);
      List<Candle> chunkSpot = sliceToRange(allSpotCandles, chunk);
      List<Candle> chunkSubBars = sliceSubBarsToRange(allPerpSubBars, chunk, iv);
      return FundingArbHarnessV2.run(
          s, chunk, chunkSpot, chunkFunding, sym, iv, cfg, Optional.empty(), chunkSubBars);
    };
  }

  private static List<Candle> sliceToRange(List<Candle> all, List<Candle> chunk) {
    if (chunk.isEmpty() || all.isEmpty()) {
      return List.of();
    }
    Instant from = chunk.get(0).openTime();
    Instant to = chunk.get(chunk.size() - 1).openTime();
    return all.stream()
        .filter(c -> !c.openTime().isBefore(from) && !c.openTime().isAfter(to))
        .toList();
  }

  /**
   * Like {@link #sliceToRange} but extends the upper bound to the <em>end</em> of the chunk's last
   * decision bar ({@code lastOpenTime + interval.duration()}), not just its start — a sub-bar
   * series needs every finer-grained candle strictly inside the last decision bar's own window,
   * which a naive openTime-to-openTime range would silently truncate.
   */
  private static List<Candle> sliceSubBarsToRange(
      List<Candle> all, List<Candle> chunk, CandleInterval interval) {
    if (chunk.isEmpty() || all.isEmpty()) {
      return List.of();
    }
    Instant from = chunk.get(0).openTime();
    Instant to = chunk.get(chunk.size() - 1).openTime().plus(interval.duration());
    return all.stream()
        .filter(c -> !c.openTime().isBefore(from) && c.openTime().isBefore(to))
        .toList();
  }

  private static List<FundingEvent> sliceFundingToRange(
      List<FundingEvent> all, List<Candle> chunk) {
    if (chunk.isEmpty()) {
      return List.of();
    }
    Instant from = chunk.get(0).openTime();
    Instant to = chunk.get(chunk.size() - 1).openTime();
    return all.stream().filter(f -> !f.time().isBefore(from) && !f.time().isAfter(to)).toList();
  }
}
