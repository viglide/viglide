package app.viglide.research.k3;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.domain.Direction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link S1Replication}. */
class S1ReplicationTest {

  private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");

  @Test
  void cellWithFewerThanTwoObservationsDoesNotQualify() {
    Map<String, Map<Integer, List<SignalObservation>>> byPairYear = new HashMap<>();
    byPairYear.put(
        "BTCUSDT",
        Map.of(
            2024, List.of(new SignalObservation("BTCUSDT", 2024, T0, 0.7, Direction.BUY, 0.01))));

    List<S1Replication.CellResult> cells = S1Replication.evaluateCells(byPairYear, 200, 1L);

    assertThat(cells).hasSize(1);
    assertThat(cells.get(0).qualifies()).isFalse();
  }

  @Test
  void strongInformativeCellQualifies() {
    // A cell where higher stated probability lines up cleanly with a bigger directional return --
    // an obviously-real relationship the null test should not be able to explain away.
    List<SignalObservation> cell = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      double p = 0.5 + (i / 30.0) * 0.4; // 0.50 .. 0.90, monotonically increasing
      double ret = (i / 30.0) * 0.05; // 0 .. 5%, monotonically increasing with p
      cell.add(new SignalObservation("BTCUSDT", 2024, T0, p, Direction.BUY, ret));
    }
    Map<String, Map<Integer, List<SignalObservation>>> byPairYear =
        Map.of("BTCUSDT", Map.of(2024, cell));

    List<S1Replication.CellResult> cells = S1Replication.evaluateCells(byPairYear, 200, 1L);

    assertThat(cells).hasSize(1);
    assertThat(cells.get(0).ic()).isGreaterThan(0.0);
    assertThat(cells.get(0).qualifies()).isTrue();
  }

  @Test
  void noiseCellTypicallyDoesNotQualify() {
    Random rng = new Random(5);
    List<SignalObservation> cell = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      cell.add(
          new SignalObservation(
              "ETHUSDT", 2024, T0, rng.nextDouble(), Direction.BUY, rng.nextDouble() - 0.5));
    }
    Map<String, Map<Integer, List<SignalObservation>>> byPairYear =
        Map.of("ETHUSDT", Map.of(2024, cell));

    List<S1Replication.CellResult> cells = S1Replication.evaluateCells(byPairYear, 200, 1L);

    assertThat(cells.get(0).qualifies()).isFalse();
  }

  @Test
  void passesReplicationRequiresEachPairToQualifyInEnoughDistinctYears() {
    // BTC qualifies in two distinct years; ETH qualifies in only one. With minYearsPerPair=2,
    // only BTC counts -- one pair short of a minPairs=2 requirement.
    List<S1Replication.CellResult> cells =
        List.of(
            new S1Replication.CellResult("BTCUSDT", 2023, 0.5, 0.97, 30),
            new S1Replication.CellResult("BTCUSDT", 2024, 0.4, 0.96, 30),
            new S1Replication.CellResult("ETHUSDT", 2023, 0.5, 0.97, 30),
            new S1Replication.CellResult("ETHUSDT", 2024, -0.1, 0.20, 30)); // not qualifying

    assertThat(S1Replication.passesReplication(cells, 2, 2)).isFalse();
    assertThat(S1Replication.passesReplication(cells, 1, 2)).isTrue();
  }

  @Test
  void passesReplicationTrueWhenEnoughPairsEachQualifyInEnoughYears() {
    List<S1Replication.CellResult> cells = new ArrayList<>();
    for (String pair : List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT")) {
      cells.add(new S1Replication.CellResult(pair, 2023, 0.5, 0.97, 30));
      cells.add(new S1Replication.CellResult(pair, 2024, 0.4, 0.96, 30));
    }

    assertThat(S1Replication.passesReplication(cells, 4, 2)).isTrue();
  }

  @Test
  void qualifyingCellRequiresBothPositiveIcAndBeatingNull() {
    S1Replication.CellResult positiveButNotSignificant =
        new S1Replication.CellResult("BTCUSDT", 2024, 0.1, 0.80, 30);
    S1Replication.CellResult significantButNegative =
        new S1Replication.CellResult("BTCUSDT", 2024, -0.5, 0.99, 30);
    S1Replication.CellResult both = new S1Replication.CellResult("BTCUSDT", 2024, 0.5, 0.99, 30);

    assertThat(positiveButNotSignificant.qualifies()).isFalse();
    assertThat(significantButNegative.qualifies()).isFalse();
    assertThat(both.qualifies()).isTrue();
  }
}
