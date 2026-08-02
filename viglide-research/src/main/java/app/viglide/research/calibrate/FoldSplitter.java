package app.viglide.research.calibrate;

import app.viglide.core.domain.Candle;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a chronologically-ordered candle list into K equal-size sequential chunks for walk-forward
 * validation. Each candidate parameter set is evaluated independently on every fold and the median
 * Sharpe is the headline ranking score.
 */
public final class FoldSplitter {

  private FoldSplitter() {}

  /**
   * @param candles full chronologically-ordered candle list
   * @param folds number of equal-size sequential chunks (K ≥ 2 enforced)
   * @return immutable list of K chunks; the last chunk absorbs any remainder
   * @throws IllegalArgumentException if {@code folds < 2} or {@code candles.size() < folds}
   */
  public static List<List<Candle>> split(List<Candle> candles, int folds) {
    if (folds < 2) throw new IllegalArgumentException("folds must be >= 2, got: " + folds);
    if (candles.size() < folds) {
      throw new IllegalArgumentException(
          "need at least "
              + folds
              + " candles for "
              + folds
              + "-fold split; got "
              + candles.size());
    }

    int chunk = candles.size() / folds;
    List<List<Candle>> out = new ArrayList<>(folds);
    for (int i = 0; i < folds; i++) {
      int from = i * chunk;
      int to = (i == folds - 1) ? candles.size() : from + chunk;
      out.add(List.copyOf(candles.subList(from, to)));
    }
    return List.copyOf(out);
  }

  /**
   * One fold's own candles, plus (PLAN-009 Task D) up to {@code maxPrefixBars} candles taken from
   * immediately before the fold's start in the original series — used to warm indicators up
   * <em>before</em> the fold begins instead of burning the fold's own first {@code warmupBars}
   * candles on a cold start. {@code prefix} is empty for fold 0 (nothing precedes it in the
   * training window; an unavoidable, non-artifactual cold start) and for any fold whose start
   * coincides with index 0 of the series.
   */
  public record FoldWindow(List<Candle> prefix, List<Candle> chunk) {
    public FoldWindow {
      prefix = List.copyOf(prefix);
      chunk = List.copyOf(chunk);
    }
  }

  /**
   * Same K-way split as {@link #split}, but each fold also carries its own warm-up {@code prefix}
   * (PLAN-009 Task D — fixes the "cold start per fold" artifact: indicators re-warming inside each
   * fold silently shrinks usable in-fold bars, worse at coarse intervals). Fold boundaries are
   * identical to {@link #split} — only the addition of a prefix is new, so a caller that ignores
   * {@code prefix} sees byte-identical {@code chunk} values to the original method.
   *
   * @param maxPrefixBars the most prefix candles any fold may borrow (typically the candidate's
   *     {@code warmupBars}); {@code 0} degenerates to {@link #split}'s original behaviour with all
   *     prefixes empty
   */
  public static List<FoldWindow> splitWithPrefix(
      List<Candle> candles, int folds, int maxPrefixBars) {
    if (folds < 2) throw new IllegalArgumentException("folds must be >= 2, got: " + folds);
    if (candles.size() < folds) {
      throw new IllegalArgumentException(
          "need at least "
              + folds
              + " candles for "
              + folds
              + "-fold split; got "
              + candles.size());
    }
    if (maxPrefixBars < 0) {
      throw new IllegalArgumentException("maxPrefixBars must be >= 0, got: " + maxPrefixBars);
    }

    int chunk = candles.size() / folds;
    List<FoldWindow> out = new ArrayList<>(folds);
    for (int i = 0; i < folds; i++) {
      int from = i * chunk;
      int to = (i == folds - 1) ? candles.size() : from + chunk;
      int prefixFrom = Math.max(0, from - maxPrefixBars);
      out.add(new FoldWindow(candles.subList(prefixFrom, from), candles.subList(from, to)));
    }
    return List.copyOf(out);
  }

  /**
   * One fold's warm-up prefix, own chunk (both identical to {@link #splitWithPrefix}), and how many
   * of the chunk's <em>own</em> leading candles are still not independent evidence from the
   * previous fold (PLAN-013 Task F, review finding F7 — purged K-fold with embargo, adapted to this
   * harness's own architecture).
   *
   * <p>There is no separate train/test split here — every candidate is scored identically on every
   * fold. The leak is narrower but real: fold {@code i}'s {@code prefix} is a suffix of fold {@code
   * i-1}'s own {@code chunk} (by construction — {@link #splitWithPrefix} draws the prefix from
   * immediately before the fold's own start), and {@code BacktestHarness} evaluates the strategy on
   * a fixed-size rolling window of exactly {@code warmupBars} candles. That means fold {@code i}'s
   * decisions don't draw a window built <em>purely</em> from fold {@code i}'s own data until {@code
   * warmupBars} of the fold's own candles have been consumed — before that, the window is still
   * substantially fold {@code i-1}'s own candles, re-used. For an autocorrelated series (funding
   * rates strongly so — PLAN-013 §0), fold {@code i}'s earliest scored decisions are therefore not
   * an independent look at the candidate's performance; they are largely a re-scoring of what fold
   * {@code i-1} already contributed.
   *
   * @param purgedBars how many of {@code chunk}'s own leading candles a caller should exclude from
   *     scoring (not from evaluation — the strategy still sees them, for indicator continuity) —
   *     {@code min(chunk.size(), min(prefix.size(), warmupBars) + embargoBars)}. Fold 0 (empty
   *     prefix) has no contamination to purge beyond the embargo itself: there is no prior fold to
   *     have leaked from.
   */
  public record PurgedFoldWindow(List<Candle> prefix, List<Candle> chunk, int purgedBars) {
    public PurgedFoldWindow {
      prefix = List.copyOf(prefix);
      chunk = List.copyOf(chunk);
      if (purgedBars < 0) {
        throw new IllegalArgumentException("purgedBars must be >= 0, got: " + purgedBars);
      }
    }

    /** {@code chunk}'s own candles that are clear of both prefix contamination and the embargo. */
    public List<Candle> scoreableChunk() {
      return chunk.subList(purgedBars, chunk.size());
    }
  }

  /**
   * {@link #splitWithPrefix}, plus an embargo (PLAN-013 Task F, review finding F7): each {@link
   * PurgedFoldWindow#purgedBars()} tells the caller how many of that fold's own leading candles to
   * exclude from scoring, on top of the pre-existing prefix/warm-up mechanics (unchanged — see
   * {@link #splitWithPrefix}'s own Javadoc; this method purges <em>around</em> it, not through it).
   *
   * @param embargoBars extra candles to purge beyond the point the rolling window is nominally
   *     clear of prefix contamination — a safety margin against serial correlation the nominal
   *     {@code warmupBars} lookback doesn't fully capture. {@code 0} makes every fold's {@code
   *     purgedBars} exactly its own prefix-contamination length — never negative, so a caller
   *     summing {@code purgedBars} across folds gets the same non-zero purge {@code
   *     splitWithPrefix} always implied but never reported; {@code embargoBars=0} does not mean
   *     "reproduce calibration with zero purging," it means "purge only what the existing prefix
   *     mechanism already required, report it, add no extra margin."
   */
  public static List<PurgedFoldWindow> splitPurged(
      List<Candle> candles, int folds, int warmupBars, int embargoBars) {
    if (embargoBars < 0) {
      throw new IllegalArgumentException("embargoBars must be >= 0, got: " + embargoBars);
    }
    List<FoldWindow> base = splitWithPrefix(candles, folds, warmupBars);
    List<PurgedFoldWindow> out = new ArrayList<>(base.size());
    for (FoldWindow w : base) {
      int contamination = w.prefix().isEmpty() ? 0 : Math.min(w.prefix().size(), warmupBars);
      int purged = Math.min(w.chunk().size(), contamination + embargoBars);
      out.add(new PurgedFoldWindow(w.prefix(), w.chunk(), purged));
    }
    return List.copyOf(out);
  }

  /**
   * Purge accounting for a whole run (PLAN-013 Task F): {@code totalChunkBars} is every fold's own
   * {@code chunk} summed, {@code totalPurgedBars} is every fold's {@code purgedBars} summed. A run
   * that purges most of its data ({@code purgeFraction} near 1.0) is telling you the fold count is
   * too high for the lookback — not a bug to silence, a configuration smell to report.
   */
  public record PurgeReport(int totalChunkBars, int totalPurgedBars) {
    public double purgeFraction() {
      return totalChunkBars == 0 ? 0.0 : (double) totalPurgedBars / totalChunkBars;
    }
  }

  public static PurgeReport purgeReport(List<PurgedFoldWindow> windows) {
    int totalChunk = 0;
    int totalPurged = 0;
    for (PurgedFoldWindow w : windows) {
      totalChunk += w.chunk().size();
      totalPurged += w.purgedBars();
    }
    return new PurgeReport(totalChunk, totalPurged);
  }
}
