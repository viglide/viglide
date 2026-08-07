package app.viglide.research.calibrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.PortfolioContext;
import app.viglide.core.domain.PositionShape;
import app.viglide.core.domain.TargetPosition;
import app.viglide.core.spi.PortfolioStrategy;
import app.viglide.core.spi.StrategyMetadata;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PortfolioFoldSplitter}. */
class PortfolioFoldSplitterTest {

  @Test
  void splitPurged_rejectsTooFewFolds() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> PortfolioFoldSplitter.splitPurged(Map.of("AAA", ascending(100)), 1, 10, 0));
  }

  @Test
  void splitPurged_rejectsNegativeEmbargo() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> PortfolioFoldSplitter.splitPurged(Map.of("AAA", ascending(100)), 5, 10, -1));
  }

  @Test
  void splitPurged_rejectsEmptyPanel() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PortfolioFoldSplitter.splitPurged(Map.of(), 5, 10, 0));
  }

  @Test
  void splitPurged_alignedPanel_boundariesMatchSingleSymbolSplitter() {
    Map<String, List<Candle>> panel = new LinkedHashMap<>();
    panel.put("AAA", ascending(100));
    panel.put("BBB", ascending(100));

    List<PortfolioFoldSplitter.PortfolioFoldWindow> crossSectional =
        PortfolioFoldSplitter.splitPurged(panel, 5, 10, 0);
    List<FoldSplitter.PurgedFoldWindow> singleSymbol =
        FoldSplitter.splitPurged(ascending(100), 5, 10, 0);

    assertThat(crossSectional).hasSize(5);
    for (int i = 0; i < 5; i++) {
      assertThat(crossSectional.get(i).purgedBars()).isEqualTo(singleSymbol.get(i).purgedBars());
      assertThat(crossSectional.get(i).chunkBySymbol().get("AAA"))
          .isEqualTo(singleSymbol.get(i).chunk());
      assertThat(crossSectional.get(i).chunkBySymbol().get("BBB"))
          .isEqualTo(singleSymbol.get(i).chunk());
    }
  }

  /**
   * The cross-sectional acceptance criterion: a leak planted in ONE symbol's series is invisible to
   * a {@link PortfolioStrategy} that evaluates every symbol's context together, when purged scoring
   * is used, and visible when it is not — mirrors {@code
   * FoldSplitterTest#splitPurged_closesTheLeak_lookaheadStrategyScoresWellUnpurged_notPurged}
   * exactly, but for the shape a single-symbol splitter cannot exercise: the leak is in a symbol
   * OTHER than the one whose fold boundary is nominally being checked (here, "BBB" is the observing
   * symbol; "AAA" carries the marker) — a PortfolioStrategy that pools across symbols would still
   * see it if fold boundaries desynchronised between symbols.
   */
  @Test
  void splitPurged_closesTheCrossSymbolLeak_lookaheadStrategyScoresWellUnpurged_notPurged() {
    int warmupBars = 10;
    List<Candle> aaa = withMarkerAt(ascending(100), 19, "999"); // last candle of fold 0's chunk
    List<Candle> bbb = ascending(100); // never carries the marker itself

    Map<String, List<Candle>> panel = new LinkedHashMap<>();
    panel.put("AAA", aaa);
    panel.put("BBB", bbb);

    List<PortfolioFoldSplitter.PortfolioFoldWindow> purged =
        PortfolioFoldSplitter.splitPurged(panel, 5, warmupBars, 0);
    PortfolioFoldSplitter.PortfolioFoldWindow fold1 = purged.get(1);

    LeakDetectorPortfolioStrategy strategy =
        new LeakDetectorPortfolioStrategy(new BigDecimal("999"));

    // Naive: replay prefix+chunk in full, count every bar from chunk[0] onward.
    int leaksInNaiveScoring =
        countLeakDetections(strategy, fold1.prefixBySymbol(), fold1.chunkBySymbol(), 0, warmupBars);
    // Purged: same replay (the window must still see every candle in order), counting only from
    // purgedBars onward -- exactly what a caller using scoreableChunkBySymbol() would score.
    int leaksInPurgedScoring =
        countLeakDetections(
            strategy,
            fold1.prefixBySymbol(),
            fold1.chunkBySymbol(),
            fold1.purgedBars(),
            warmupBars);

    assertThat(leaksInNaiveScoring).isGreaterThan(0);
    assertThat(leaksInPurgedScoring).isEqualTo(0);
  }

  /**
   * Replays the union calendar bar by bar, maintaining one sliding {@code Deque} per symbol
   * (mirroring {@link app.viglide.core.backtest.PortfolioBacktestHarness}'s own window mechanic),
   * and evaluates the strategy once per bar against every symbol's current window. Counting starts
   * only once {@code countFromChunkIndex} of the chunk has been consumed, matching {@code
   * FoldSplitterTest#countLeakDetections}'s own convention.
   */
  private static int countLeakDetections(
      LeakDetectorPortfolioStrategy strategy,
      Map<String, List<Candle>> prefixBySymbol,
      Map<String, List<Candle>> chunkBySymbol,
      int countFromChunkIndex,
      int warmupBars) {
    List<String> symbols = new ArrayList<>(chunkBySymbol.keySet());
    Map<String, ArrayDeque<Candle>> windows = new LinkedHashMap<>();
    for (String s : symbols) {
      windows.put(s, new ArrayDeque<>(warmupBars));
      for (Candle c : prefixBySymbol.getOrDefault(s, List.of())) {
        ArrayDeque<Candle> w = windows.get(s);
        if (w.size() >= warmupBars) w.pollFirst();
        w.addLast(c);
      }
    }

    int chunkLen = chunkBySymbol.get(symbols.get(0)).size();
    int detections = 0;
    for (int i = 0; i < chunkLen; i++) {
      Map<String, MarketContext> ctxBySymbol = new TreeMap<>();
      Instant asOf = null;
      for (String s : symbols) {
        Candle c = chunkBySymbol.get(s).get(i);
        ArrayDeque<Candle> w = windows.get(s);
        if (w.size() >= warmupBars) w.pollFirst();
        w.addLast(c);
        ctxBySymbol.put(s, new MarketContext(s, CandleInterval.ONE_HOUR, new ArrayList<>(w)));
        asOf = c.openTime();
      }
      if (i < countFromChunkIndex) {
        continue;
      }
      PortfolioContext portfolioContext = new PortfolioContext(asOf, ctxBySymbol);
      if (!strategy.evaluate(portfolioContext).isEmpty()) {
        detections++;
      }
    }
    return detections;
  }

  /** Detects the marker if it is visible in ANY symbol's current window. */
  private record LeakDetectorPortfolioStrategy(BigDecimal markerClose)
      implements PortfolioStrategy {
    @Override
    public List<TargetPosition> evaluate(PortfolioContext context) {
      boolean markerVisible =
          context.bySymbol().values().stream()
              .flatMap(ctx -> ctx.candles().stream())
              .anyMatch(c -> c.close().compareTo(markerClose) == 0);
      if (!markerVisible) {
        return List.of();
      }
      return List.of(
          new TargetPosition(
              context.bySymbol().keySet().iterator().next(),
              BigDecimal.ONE,
              PositionShape.SPOT_ONLY,
              List.of(),
              "leak detected"));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("LeakDetectorPortfolio", "0.0.1", "PLAN-019 Task D test-only");
    }
  }

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

  private static List<Candle> withMarkerAt(List<Candle> candles, int index, String closeStr) {
    List<Candle> out = new ArrayList<>(candles);
    Candle orig = out.get(index);
    BigDecimal marker = new BigDecimal(closeStr);
    out.set(index, new Candle(orig.openTime(), marker, marker, marker, marker, orig.volume()));
    return out;
  }
}
