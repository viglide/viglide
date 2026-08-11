package app.viglide.research.k3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.viglide.core.domain.Direction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
  void reachableWhenSomeThresholdClearsFloorWithEnoughTrades() {
    List<EconomicReachability.ReachabilityPoint> points =
        List.of(
            new EconomicReachability.ReachabilityPoint(0.5, 50, 30.0), // below floor
            new EconomicReachability.ReachabilityPoint(0.8, 20, 80.0)); // clears floor
    assertThat(EconomicReachability.reachable(points, 60.0, 10)).isTrue();
  }

  @Test
  void notReachableWhenFloorClearingThresholdHasTooFewTrades() {
    // fundingarb's shape: a real edge that fails at every practical threshold, or here, one that
    // only "clears" on too small a sample to trust (S6's per-pair floor doing its job).
    List<EconomicReachability.ReachabilityPoint> points =
        List.of(new EconomicReachability.ReachabilityPoint(0.95, 3, 200.0));
    assertThat(EconomicReachability.reachable(points, 60.0, 30)).isFalse();
  }

  @Test
  void notReachableWhenNoThresholdClearsTheFloor() {
    List<EconomicReachability.ReachabilityPoint> points =
        List.of(
            new EconomicReachability.ReachabilityPoint(0.5, 100, 10.0),
            new EconomicReachability.ReachabilityPoint(0.9, 50, 12.0));
    assertThat(EconomicReachability.reachable(points, 60.0, 10)).isFalse();
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
