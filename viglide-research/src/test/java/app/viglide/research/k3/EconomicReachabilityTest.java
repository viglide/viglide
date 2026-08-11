package app.viglide.research.k3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.viglide.core.domain.Direction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link EconomicReachability}. */
class EconomicReachabilityTest {

  private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");

  @Test
  void sweepComputesMeanDirectionalEdgeInBpsPerThreshold() {
    List<SignalObservation> obs =
        List.of(
            new SignalObservation("BTCUSDT", 2024, T0, 0.6, Direction.BUY, 0.01), // 100 bps
            new SignalObservation("BTCUSDT", 2024, T0, 0.9, Direction.BUY, 0.02)); // 200 bps

    List<EconomicReachability.ReachabilityPoint> sweep =
        EconomicReachability.sweep(obs, new double[] {0.5, 0.8});

    assertThat(sweep.get(0).probabilityThreshold()).isEqualTo(0.5);
    assertThat(sweep.get(0).tradeCount()).isEqualTo(2);
    assertThat(sweep.get(0).edgeBps()).isCloseTo(150.0, within(1e-9)); // mean of 100 and 200 bps

    assertThat(sweep.get(1).probabilityThreshold()).isEqualTo(0.8);
    assertThat(sweep.get(1).tradeCount()).isEqualTo(1);
    assertThat(sweep.get(1).edgeBps()).isCloseTo(200.0, within(1e-9));
  }

  @Test
  void emptyThresholdBucketHasZeroEdgeNotAnError() {
    List<SignalObservation> obs =
        List.of(new SignalObservation("BTCUSDT", 2024, T0, 0.5, Direction.BUY, 0.01));
    List<EconomicReachability.ReachabilityPoint> sweep =
        EconomicReachability.sweep(obs, new double[] {0.99});
    assertThat(sweep.get(0).tradeCount()).isEqualTo(0);
    assertThat(sweep.get(0).edgeBps()).isEqualTo(0.0);
  }

  @Test
  void sweepBreaksSurvivingObservationsDownByPair() {
    List<SignalObservation> obs =
        List.of(
            new SignalObservation("BTCUSDT", 2024, T0, 0.9, Direction.BUY, 0.01),
            new SignalObservation("BTCUSDT", 2024, T0, 0.9, Direction.BUY, 0.01),
            new SignalObservation("ETHUSDT", 2024, T0, 0.9, Direction.BUY, 0.01),
            new SignalObservation("SOLUSDT", 2024, T0, 0.5, Direction.BUY, 0.01));

    EconomicReachability.ReachabilityPoint point =
        EconomicReachability.sweep(obs, new double[] {0.8}).get(0);

    assertThat(point.tradeCountByPair())
        .as("SOLUSDT fired only below the threshold, so it is absent rather than zero")
        .containsExactlyInAnyOrderEntriesOf(Map.of("BTCUSDT", 2, "ETHUSDT", 1));
    assertThat(point.pairsWithAtLeast(2)).isEqualTo(1);
  }

  @Test
  void reachableWhenSomeThresholdClearsFloorAcrossEnoughPairs() {
    List<EconomicReachability.ReachabilityPoint> points =
        List.of(
            point(0.5, 30.0, Map.of("BTCUSDT", 25, "ETHUSDT", 25)), // below floor
            point(0.8, 80.0, Map.of("BTCUSDT", 10, "ETHUSDT", 10))); // clears floor
    assertThat(EconomicReachability.reachable(points, 60.0, 10, 2)).isTrue();
  }

  @Test
  void notReachableWhenFloorClearingThresholdHasTooFewTradesPerPair() {
    // A real edge that only "clears" on too small a sample to trust.
    assertThat(
            EconomicReachability.reachable(
                List.of(point(0.95, 200.0, Map.of("BTCUSDT", 3))), 60.0, 30, 1))
        .isFalse();
  }

  @Test
  void notReachableWhenTheSurvivingSampleIsConcentratedInOnePair() {
    // The defect this floor exists to close: 120 surviving observations, comfortably over any
    // pooled count, but all of them on one pair. S6's per-pair floor cannot catch this -- it
    // constrains the whole sample, not the high-probability subset a threshold selects -- so a
    // signal that works on BTC alone would otherwise be reported as economically reachable.
    List<EconomicReachability.ReachabilityPoint> points =
        List.of(point(0.9, 400.0, Map.of("BTCUSDT", 120, "ETHUSDT", 2)));

    assertThat(EconomicReachability.reachable(points, 60.0, 30, 4)).isFalse();
    assertThat(EconomicReachability.reachable(points, 60.0, 30, 1))
        .as("...and it does pass once the pre-registration only asks for one pair")
        .isTrue();
  }

  @Test
  void notReachableWhenNoThresholdClearsTheFloor() {
    List<EconomicReachability.ReachabilityPoint> points =
        List.of(
            point(0.5, 10.0, Map.of("BTCUSDT", 50, "ETHUSDT", 50)),
            point(0.9, 12.0, Map.of("BTCUSDT", 25, "ETHUSDT", 25)));
    assertThat(EconomicReachability.reachable(points, 60.0, 10, 2)).isFalse();
  }

  private static EconomicReachability.ReachabilityPoint point(
      double threshold, double edgeBps, Map<String, Integer> byPair) {
    int total = byPair.values().stream().mapToInt(Integer::intValue).sum();
    return new EconomicReachability.ReachabilityPoint(threshold, total, edgeBps, byPair);
  }

  @Test
  void sweepFiltersInclusivelyOnThreshold() {
    List<SignalObservation> obs = new ArrayList<>();
    obs.add(new SignalObservation("BTCUSDT", 2024, T0, 0.7, Direction.BUY, 0.01));
    List<EconomicReachability.ReachabilityPoint> sweep =
        EconomicReachability.sweep(obs, new double[] {0.7});
    assertThat(sweep.get(0).tradeCount()).isEqualTo(1);
  }
}
