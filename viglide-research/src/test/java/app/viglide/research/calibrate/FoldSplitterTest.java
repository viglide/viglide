package app.viglide.research.calibrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.spi.StrategyMetadata;
import app.viglide.core.spi.TradingStrategy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FoldSplitter}. */
class FoldSplitterTest {

  @Test
  void splitsIntoKEqualChunks_withRemainderInLast() {
    List<Candle> candles = ascending(20);
    List<List<Candle>> chunks = FoldSplitter.split(candles, 4);
    assertThat(chunks).hasSize(4);
    chunks.forEach(c -> assertThat(c).hasSize(5));
  }

  @Test
  void lastChunkAbsorbsRemainder() {
    List<Candle> candles = ascending(23);
    List<List<Candle>> chunks = FoldSplitter.split(candles, 4);
    assertThat(chunks.get(0)).hasSize(5);
    assertThat(chunks.get(3)).hasSize(8); // 5 + 3 remainder
  }

  @Test
  void everyCandleIsInExactlyOneChunk() {
    List<Candle> candles = ascending(100);
    List<List<Candle>> chunks = FoldSplitter.split(candles, 5);
    int total = chunks.stream().mapToInt(List::size).sum();
    assertThat(total).isEqualTo(100);
    // Sequential, no overlap: the timestamps of chunk i+1 strictly follow chunk i.
    for (int i = 1; i < chunks.size(); i++) {
      Instant lastOfPrev = chunks.get(i - 1).getLast().openTime();
      Instant firstOfNext = chunks.get(i).getFirst().openTime();
      assertThat(firstOfNext).isAfter(lastOfPrev);
    }
  }

  @Test
  void rejectsTooFewFolds() {
    assertThatIllegalArgumentException().isThrownBy(() -> FoldSplitter.split(ascending(10), 1));
  }

  @Test
  void rejectsFewerCandlesThanFolds() {
    assertThatIllegalArgumentException().isThrownBy(() -> FoldSplitter.split(ascending(3), 5));
  }

  // ── PLAN-009 Task D: splitWithPrefix ────────────────────────────────────────────────────────

  @Test
  void splitWithPrefix_chunksAreByteIdenticalToSplit() {
    List<Candle> candles = ascending(23);
    List<List<Candle>> plain = FoldSplitter.split(candles, 4);
    List<FoldSplitter.FoldWindow> withPrefix = FoldSplitter.splitWithPrefix(candles, 4, 5);
    assertThat(withPrefix).hasSize(4);
    for (int i = 0; i < plain.size(); i++) {
      assertThat(withPrefix.get(i).chunk()).isEqualTo(plain.get(i));
    }
  }

  @Test
  void splitWithPrefix_fold0HasNoPrefix() {
    List<Candle> candles = ascending(100);
    List<FoldSplitter.FoldWindow> windows = FoldSplitter.splitWithPrefix(candles, 5, 10);
    assertThat(windows.get(0).prefix()).isEmpty();
  }

  @Test
  void splitWithPrefix_laterFoldsGetExactlyMaxPrefixBarsImmediatelyBeforeTheirStart() {
    List<Candle> candles = ascending(100); // 5 folds of 20 each
    List<FoldSplitter.FoldWindow> windows = FoldSplitter.splitWithPrefix(candles, 5, 10);
    FoldSplitter.FoldWindow fold1 = windows.get(1); // starts at index 20
    assertThat(fold1.prefix()).hasSize(10);
    // The prefix is exactly candles[10..20) -- immediately before fold 1's own [20,40).
    assertThat(fold1.prefix()).isEqualTo(candles.subList(10, 20));
    assertThat(fold1.chunk().getFirst().openTime()).isAfter(fold1.prefix().getLast().openTime());
  }

  @Test
  void splitWithPrefix_prefixIsTruncatedNearTheSeriesStart() {
    List<Candle> candles = ascending(100); // 5 folds of 20 each; fold 1 starts at index 20
    // Ask for a 30-bar prefix, but only 20 candles precede fold 1's start -- must not reach
    // negative or wrap into fold 0's own bars beyond what actually exists before index 20.
    List<FoldSplitter.FoldWindow> windows = FoldSplitter.splitWithPrefix(candles, 5, 30);
    assertThat(windows.get(1).prefix()).hasSize(20); // capped at what's actually available
    assertThat(windows.get(1).prefix()).isEqualTo(candles.subList(0, 20));
  }

  @Test
  void splitWithPrefix_zeroMaxPrefixDegeneratesToSplit() {
    List<Candle> candles = ascending(23);
    List<List<Candle>> plain = FoldSplitter.split(candles, 4);
    List<FoldSplitter.FoldWindow> withPrefix = FoldSplitter.splitWithPrefix(candles, 4, 0);
    for (int i = 0; i < plain.size(); i++) {
      assertThat(withPrefix.get(i).prefix()).isEmpty();
      assertThat(withPrefix.get(i).chunk()).isEqualTo(plain.get(i));
    }
  }

  @Test
  void splitWithPrefix_rejectsNegativeMaxPrefixBars() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> FoldSplitter.splitWithPrefix(ascending(20), 4, -1));
  }

  // ── PLAN-013 Task F: splitPurged (finding F7) ───────────────────────────────────────────────

  @Test
  void splitPurged_fold0_purgesOnlyTheEmbargo_noPriorFoldToLeakFrom() {
    List<Candle> candles = ascending(100); // 5 folds of 20 each
    List<FoldSplitter.PurgedFoldWindow> windows = FoldSplitter.splitPurged(candles, 5, 10, 3);
    assertThat(windows.get(0).prefix()).isEmpty();
    assertThat(windows.get(0).purgedBars()).isEqualTo(3); // embargo only, no contamination
  }

  @Test
  void splitPurged_laterFold_purgesContaminationPlusEmbargo() {
    List<Candle> candles = ascending(100); // 5 folds of 20 each, warmupBars=10 fits fully as prefix
    List<FoldSplitter.PurgedFoldWindow> windows = FoldSplitter.splitPurged(candles, 5, 10, 3);
    FoldSplitter.PurgedFoldWindow fold1 = windows.get(1);
    assertThat(fold1.prefix()).hasSize(10);
    assertThat(fold1.purgedBars()).isEqualTo(10 + 3); // full prefix contamination + embargo
    assertThat(fold1.scoreableChunk()).hasSize(fold1.chunk().size() - 13);
  }

  @Test
  void splitPurged_embargoZero_purgesExactlyThePrefixContamination() {
    // "embargoBars=0" means "purge only what splitWithPrefix's own mechanics already implied,
    // report it" -- not "purge nothing." Existing calibration behaviour (which never purged at
    // all) stays reproducible by using splitWithPrefix directly, unaffected by this new method.
    List<Candle> candles = ascending(100);
    List<FoldSplitter.PurgedFoldWindow> windows = FoldSplitter.splitPurged(candles, 5, 10, 0);
    assertThat(windows.get(1).purgedBars()).isEqualTo(10);
  }

  @Test
  void splitPurged_purgeNeverExceedsChunkSize_evenWithAHugeEmbargo() {
    List<Candle> candles = ascending(100); // 5 folds of 20 each
    List<FoldSplitter.PurgedFoldWindow> windows = FoldSplitter.splitPurged(candles, 5, 10, 1000);
    FoldSplitter.PurgedFoldWindow fold1 = windows.get(1);
    assertThat(fold1.purgedBars()).isEqualTo(fold1.chunk().size()); // capped, not negative-length
    assertThat(fold1.scoreableChunk()).isEmpty();
  }

  @Test
  void splitPurged_rejectsNegativeEmbargo() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> FoldSplitter.splitPurged(ascending(100), 5, 10, -1));
  }

  /**
   * The acceptance criterion, verbatim: a deliberately autocorrelated series shows the un-purged
   * splitter leaking (a lookahead-detecting strategy scores well) and the purged splitter not.
   *
   * <p>Mechanism under test (verified directly against {@code BacktestHarness}'s own sliding-
   * window code, not assumed): the strategy sees a fixed-size rolling {@code Deque} of exactly
   * {@code warmupBars} candles. Fold 1's prefix is fold 0's own last 10 candles; a single "marker"
   * candle (close=999, everything else close=100) sits at the very last position of that prefix. As
   * fold 1's own chunk is processed candle by candle, the marker does not fall out of the rolling
   * window until exactly {@code warmupBars} of fold 1's own candles have been consumed — so a
   * strategy that fires on seeing the marker anywhere in its window "detects the leak" (scores a
   * hit) for exactly the first {@code warmupBars} evaluations of the chunk if scoring naively
   * starts at {@code chunk[0]}, and for zero evaluations once scoring is restricted to {@link
   * FoldSplitter.PurgedFoldWindow#scoreableChunk()}.
   */
  @Test
  void splitPurged_closesTheLeak_lookaheadStrategyScoresWellUnpurged_notPurged() {
    int warmupBars = 10;
    List<Candle> candles = ascending(100); // 5 folds of 20 each
    candles = withMarkerAt(candles, 19, "999"); // last candle of fold 0's own chunk

    List<FoldSplitter.FoldWindow> unpurged = FoldSplitter.splitWithPrefix(candles, 5, warmupBars);
    List<FoldSplitter.PurgedFoldWindow> purged =
        FoldSplitter.splitPurged(candles, 5, warmupBars, 0);
    FoldSplitter.FoldWindow fold1Unpurged = unpurged.get(1);
    FoldSplitter.PurgedFoldWindow fold1Purged = purged.get(1);

    LeakDetectorStrategy strategy = new LeakDetectorStrategy(new BigDecimal("999"));

    // "Naive" scoring: everything from chunk[0] onward, exactly what evaluateAcrossFolds counted
    // before this task (its own foldStart filter already excludes decisions made strictly inside
    // the prefix -- this test is specifically about the part that filter does NOT catch).
    int leaksInNaiveScoring =
        countLeakDetections(strategy, fold1Unpurged.prefix(), fold1Unpurged.chunk(), 0, warmupBars);
    // Purged scoring: the window must still be replayed through EVERY chunk candle in order (the
    // marker is only evicted by the natural slide, not by skipping ahead) -- only the COUNTING
    // starts later, at purgedBars, not the window replay itself.
    int leaksInPurgedScoring =
        countLeakDetections(
            strategy,
            fold1Purged.prefix(),
            fold1Purged.chunk(),
            fold1Purged.purgedBars(),
            warmupBars);

    assertThat(leaksInNaiveScoring).isGreaterThan(0); // the strategy "scores well" (detects it)
    assertThat(leaksInPurgedScoring).isEqualTo(0); // purging closes the leak
  }

  /**
   * Replays exactly the sliding-window mechanic {@code BacktestHarness} uses (a {@code Deque} of
   * {@code warmupBars} candles, evaluate once full) over {@code prefix + fullChunk}, in order,
   * without skipping any candle — the marker is only evicted by the natural slide, so jumping
   * straight to a later starting point would leave it artificially "stuck" in the window and prove
   * nothing. Counting starts only once {@code countFromChunkIndex} of {@code fullChunk} has been
   * consumed, matching what a caller using {@link FoldSplitter.PurgedFoldWindow#purgedBars()} would
   * actually score.
   */
  private static int countLeakDetections(
      LeakDetectorStrategy strategy,
      List<Candle> prefix,
      List<Candle> fullChunk,
      int countFromChunkIndex,
      int warmupBars) {
    java.util.ArrayDeque<Candle> window = new java.util.ArrayDeque<>(warmupBars);
    int detections = 0;
    for (Candle c : prefix) {
      if (window.size() >= warmupBars) window.pollFirst();
      window.addLast(c);
    }
    for (int i = 0; i < fullChunk.size(); i++) {
      if (window.size() >= warmupBars) window.pollFirst();
      window.addLast(fullChunk.get(i));
      if (i < countFromChunkIndex) {
        continue; // still replaying to advance the window correctly, not yet scoring
      }
      MarketContext ctx =
          new MarketContext("BTCUSDT", CandleInterval.ONE_HOUR, new ArrayList<>(window), List.of());
      if (strategy.evaluate(ctx).orElseThrow().direction() == Direction.BUY) {
        detections++;
      }
    }
    return detections;
  }

  /** Fires BUY (detected) when the marker close price is anywhere in the visible window. */
  private record LeakDetectorStrategy(BigDecimal markerClose) implements TradingStrategy {
    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
      boolean markerVisible =
          ctx.candles().stream().anyMatch(c -> c.close().compareTo(markerClose) == 0);
      Direction dir = markerVisible ? Direction.BUY : Direction.HOLD;
      return Optional.of(
          new TechnicalSignal(ctx.symbol(), dir, 1.0, List.of(), "leak-detector", ctx.asOf()));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("LeakDetector", "0.0.1", "PLAN-013 Task F test-only");
    }
  }

  private static List<Candle> withMarkerAt(List<Candle> candles, int index, String closeStr) {
    List<Candle> out = new ArrayList<>(candles);
    Candle orig = out.get(index);
    BigDecimal marker = new BigDecimal(closeStr);
    out.set(index, new Candle(orig.openTime(), marker, marker, marker, marker, orig.volume()));
    return out;
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────────

  private static List<Candle> ascending(int n) {
    List<Candle> out = new ArrayList<>(n);
    Instant t = Instant.parse("2024-01-01T00:00:00Z");
    for (int i = 0; i < n; i++) {
      out.add(
          new Candle(
              t.plusSeconds(3600L * i),
              BigDecimal.valueOf(100 + i),
              BigDecimal.valueOf(100.1 + i),
              BigDecimal.valueOf(99.9 + i),
              BigDecimal.valueOf(100 + i),
              BigDecimal.valueOf(1000)));
    }
    return out;
  }
}
