package app.viglide.research.calibrate;

import app.viglide.core.domain.Candle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Cross-sectional counterpart of {@link FoldSplitter#splitPurged} (PLAN-019 Task D): purged K-fold
 * with embargo for a {@code Map<String, List<Candle>>} panel instead of one symbol's series.
 *
 * <p><strong>The cross-symbol leak {@link FoldSplitter}'s own single-symbol leak-detector test
 * never covered:</strong> a {@link app.viglide.core.spi.PortfolioStrategy} sees every symbol's
 * {@link app.viglide.core.domain.MarketContext} in the same {@link
 * app.viglide.core.domain.PortfolioContext} call — if fold boundaries were computed independently
 * per symbol (e.g. by calling {@link FoldSplitter#splitPurged} once per symbol), two symbols with
 * different candle counts or gaps would not agree on where fold {@code i} starts and ends, so a
 * candidate could be scored on fold {@code i} for one symbol while still warming up (or already
 * purged) on another — a leak the single-symbol splitter is structurally incapable of producing and
 * its own test therefore cannot catch. This class closes that by computing fold boundaries once
 * against the <strong>union calendar</strong> (every symbol's distinct {@code openTime}, sorted)
 * and slicing every symbol's own series to those same wall-clock boundaries — a symbol with a gap
 * inside a fold simply contributes fewer candles to that fold's chunk, the same tolerance {@link
 * app.viglide.core.backtest.PortfolioBacktestHarness} already applies bar by bar.
 *
 * <p>{@code purgedBars} is a single integer, shared by every symbol (measured against the union
 * calendar, not each symbol's own local index) — for the aligned single-cadence panels this repo's
 * real corpus actually has, that is exactly right; for a panel with per-symbol gaps inside the
 * purge window, it purges a few more of that symbol's own bars than the bare minimum, never fewer —
 * the safe direction for a leak guard to err in.
 */
public final class PortfolioFoldSplitter {

  private PortfolioFoldSplitter() {}

  /**
   * One fold's per-symbol prefix and chunk, plus how many of {@code chunk}'s own leading
   * (calendar-indexed) bars are still contaminated — mirrors {@link FoldSplitter.PurgedFoldWindow}
   * exactly, just keyed by symbol.
   */
  public record PortfolioFoldWindow(
      Map<String, List<Candle>> prefixBySymbol,
      Map<String, List<Candle>> chunkBySymbol,
      int purgedBars) {

    public PortfolioFoldWindow {
      prefixBySymbol = Map.copyOf(prefixBySymbol);
      chunkBySymbol = Map.copyOf(chunkBySymbol);
      if (purgedBars < 0) {
        throw new IllegalArgumentException("purgedBars must be >= 0, got: " + purgedBars);
      }
    }

    /**
     * {@code chunk}'s own candles clear of both prefix contamination and the embargo, per symbol —
     * the cross-sectional {@link FoldSplitter.PurgedFoldWindow#scoreableChunk()}.
     */
    public Map<String, List<Candle>> scoreableChunkBySymbol() {
      Map<String, List<Candle>> out = new LinkedHashMap<>();
      for (Map.Entry<String, List<Candle>> e : chunkBySymbol.entrySet()) {
        List<Candle> chunk = e.getValue();
        int cut = Math.min(purgedBars, chunk.size());
        out.put(e.getKey(), chunk.subList(cut, chunk.size()));
      }
      return Map.copyOf(out);
    }

    /**
     * Mirrors {@code CalibrationHarness#repackage}: moves the purged leading candles from {@code
     * chunk} into {@code prefix}, per symbol — {@code prefix' = prefix + chunk[0:purgedBars)}. A
     * caller warms indicators on {@code prefix' + scoreableChunkBySymbol()} combined and scores
     * only decisions at or after {@code scoreableChunkBySymbol()}'s own first timestamp, exactly
     * the existing single-symbol pattern.
     */
    public Map<String, List<Candle>> repackagedPrefixBySymbol() {
      Map<String, List<Candle>> out = new LinkedHashMap<>();
      for (Map.Entry<String, List<Candle>> e : chunkBySymbol.entrySet()) {
        String symbol = e.getKey();
        List<Candle> chunk = e.getValue();
        int cut = Math.min(purgedBars, chunk.size());
        List<Candle> newPrefix = new ArrayList<>(prefixBySymbol.getOrDefault(symbol, List.of()));
        newPrefix.addAll(chunk.subList(0, cut));
        out.put(symbol, newPrefix);
      }
      return Map.copyOf(out);
    }
  }

  /**
   * @param candlesBySymbol every symbol's own candle history, each ascending by {@code openTime} —
   *     different lengths and gaps are expected
   * @param folds number of equal-size sequential chunks of the union calendar (K &ge; 2 enforced)
   * @param warmupBars prefix length in union-calendar bars (typically the candidate's {@code
   *     BacktestConfig#warmupBars()})
   * @param embargoBars extra bars purged beyond the nominal prefix-contamination length, same
   *     meaning as {@link FoldSplitter#splitPurged}'s own parameter
   */
  public static List<PortfolioFoldWindow> splitPurged(
      Map<String, List<Candle>> candlesBySymbol, int folds, int warmupBars, int embargoBars) {
    if (candlesBySymbol == null || candlesBySymbol.isEmpty()) {
      throw new IllegalArgumentException("candlesBySymbol must not be empty");
    }
    if (folds < 2) {
      throw new IllegalArgumentException("folds must be >= 2, got: " + folds);
    }
    if (embargoBars < 0) {
      throw new IllegalArgumentException("embargoBars must be >= 0, got: " + embargoBars);
    }
    if (warmupBars < 0) {
      throw new IllegalArgumentException("warmupBars must be >= 0, got: " + warmupBars);
    }

    TreeSet<Instant> calendarSet = new TreeSet<>();
    for (List<Candle> candles : candlesBySymbol.values()) {
      for (Candle c : candles) {
        calendarSet.add(c.openTime());
      }
    }
    List<Instant> calendar = new ArrayList<>(calendarSet);
    if (calendar.size() < folds) {
      throw new IllegalArgumentException(
          "need at least "
              + folds
              + " distinct bar timestamps for "
              + folds
              + "-fold split; got "
              + calendar.size());
    }

    int chunkSize = calendar.size() / folds;
    List<PortfolioFoldWindow> out = new ArrayList<>(folds);
    for (int i = 0; i < folds; i++) {
      int from = i * chunkSize;
      int to = (i == folds - 1) ? calendar.size() : from + chunkSize;
      Instant chunkStart = calendar.get(from);
      Instant chunkEnd = to < calendar.size() ? calendar.get(to) : null; // null = open-ended
      int prefixFrom = Math.max(0, from - warmupBars);
      Instant prefixStart = calendar.get(prefixFrom);

      Map<String, List<Candle>> prefixBySymbol = new LinkedHashMap<>();
      Map<String, List<Candle>> chunkBySymbol = new LinkedHashMap<>();
      for (Map.Entry<String, List<Candle>> e : candlesBySymbol.entrySet()) {
        List<Candle> all = e.getValue();
        prefixBySymbol.put(e.getKey(), sliceByTime(all, prefixStart, chunkStart));
        chunkBySymbol.put(e.getKey(), sliceByTime(all, chunkStart, chunkEnd));
      }

      int contamination =
          from - prefixFrom; // calendar bars actually available as prefix, <= warmupBars
      int calendarChunkLen = to - from;
      int purged = Math.min(calendarChunkLen, contamination + embargoBars);

      out.add(new PortfolioFoldWindow(prefixBySymbol, chunkBySymbol, purged));
    }
    return List.copyOf(out);
  }

  /**
   * Candles with {@code openTime} in {@code [fromInclusive, toExclusiveOrNull)}. Assumes ascending
   * input.
   */
  private static List<Candle> sliceByTime(
      List<Candle> candles, Instant fromInclusive, Instant toExclusiveOrNull) {
    List<Candle> out = new ArrayList<>();
    for (Candle c : candles) {
      Instant t = c.openTime();
      if (t.isBefore(fromInclusive)) {
        continue;
      }
      if (toExclusiveOrNull != null && !t.isBefore(toExclusiveOrNull)) {
        break;
      }
      out.add(c);
    }
    return out;
  }
}
