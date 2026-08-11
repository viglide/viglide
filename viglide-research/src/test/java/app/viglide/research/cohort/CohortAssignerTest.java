package app.viglide.research.cohort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import app.viglide.core.domain.Candle;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CohortAssigner}. */
class CohortAssignerTest {

  private static final Instant DAY0 = Instant.parse("2024-01-01T00:00:00Z");

  /**
   * {@code days} daily candles, each with {@code close=1, volume=dollarVolumePerDay}, starting at
   * DAY0.
   */
  private static List<Candle> dailyCandles(int days, long dollarVolumePerDay) {
    List<Candle> out = new ArrayList<>();
    for (int i = 0; i < days; i++) {
      Instant t = DAY0.plus(i, ChronoUnit.DAYS);
      BigDecimal one = BigDecimal.ONE;
      out.add(new Candle(t, one, one, one, one, BigDecimal.valueOf(dollarVolumePerDay)));
    }
    return out;
  }

  @Test
  void ranksByAverageDailyDollarVolumeDescending() {
    Map<String, List<Candle>> byPair =
        new TreeMap<>(
            Map.of(
                "BTCUSDT", dailyCandles(30, 1_000_000),
                "ETHUSDT", dailyCandles(30, 500_000),
                "SOLUSDT", dailyCandles(30, 100_000)));
    Instant asOf = DAY0.plus(30, ChronoUnit.DAYS);

    List<CohortAssignment> result = CohortAssigner.assign(byPair, asOf, Duration.ofDays(30), 1);

    assertThat(result)
        .extracting(CohortAssignment::pair)
        .containsExactly("BTCUSDT", "ETHUSDT", "SOLUSDT");
    assertThat(result).extracting(CohortAssignment::advRank).containsExactly(1, 2, 3);
  }

  @Test
  void topKMajorsGetsAssignedMajor_restGetAlt() {
    Map<String, List<Candle>> byPair =
        Map.of(
            "BTCUSDT", dailyCandles(30, 1_000_000),
            "ETHUSDT", dailyCandles(30, 500_000),
            "SOLUSDT", dailyCandles(30, 100_000),
            "ADAUSDT", dailyCandles(30, 50_000));
    Instant asOf = DAY0.plus(30, ChronoUnit.DAYS);

    List<CohortAssignment> result = CohortAssigner.assign(byPair, asOf, Duration.ofDays(30), 2);

    Map<String, Cohort> cohortByPair = new TreeMap<>();
    result.forEach(a -> cohortByPair.put(a.pair(), a.cohort()));
    assertThat(cohortByPair).containsEntry("BTCUSDT", Cohort.MAJOR);
    assertThat(cohortByPair).containsEntry("ETHUSDT", Cohort.MAJOR);
    assertThat(cohortByPair).containsEntry("SOLUSDT", Cohort.ALT);
    assertThat(cohortByPair).containsEntry("ADAUSDT", Cohort.ALT);
  }

  @Test
  void candlesAtOrAfterAsOfAreNeverRead_pointInTimeGuarantee() {
    // A volume spike placed *after* asOf must not move the ranking -- otherwise a later run could
    // leak into an earlier window's assignment.
    List<Candle> btc = new ArrayList<>(dailyCandles(30, 100_000));
    Instant asOf = DAY0.plus(30, ChronoUnit.DAYS);
    BigDecimal one = BigDecimal.ONE;
    btc.add(new Candle(asOf, one, one, one, one, BigDecimal.valueOf(999_000_000L))); // future spike
    List<Candle> eth = dailyCandles(30, 500_000);

    Map<String, List<Candle>> byPair = Map.of("BTCUSDT", btc, "ETHUSDT", eth);
    List<CohortAssignment> result = CohortAssigner.assign(byPair, asOf, Duration.ofDays(30), 1);

    // ETH still ranks above BTC -- the future spike was invisible.
    assertThat(result.get(0).pair()).isEqualTo("ETHUSDT");
  }

  @Test
  void candlesBeforeTheLookbackWindowAreExcluded() {
    List<Candle> btc = new ArrayList<>();
    // A huge volume day 100 days before asOf, outside a 30-day lookback.
    BigDecimal one = BigDecimal.ONE;
    btc.add(
        new Candle(
            DAY0.minus(100, ChronoUnit.DAYS),
            one,
            one,
            one,
            one,
            BigDecimal.valueOf(999_000_000L)));
    btc.addAll(dailyCandles(30, 100_000));
    List<Candle> eth = dailyCandles(30, 500_000);

    Instant asOf = DAY0.plus(30, ChronoUnit.DAYS);
    Map<String, List<Candle>> byPair = Map.of("BTCUSDT", btc, "ETHUSDT", eth);
    List<CohortAssignment> result = CohortAssigner.assign(byPair, asOf, Duration.ofDays(30), 1);

    assertThat(result.get(0).pair()).isEqualTo("ETHUSDT");
  }

  @Test
  void pairWithNoCandlesInWindowGetsZeroAdvAndRanksLast_neverExcluded() {
    Map<String, List<Candle>> byPair =
        Map.of("BTCUSDT", dailyCandles(30, 100_000), "NEWUSDT", List.of());
    Instant asOf = DAY0.plus(30, ChronoUnit.DAYS);

    List<CohortAssignment> result = CohortAssigner.assign(byPair, asOf, Duration.ofDays(30), 1);

    assertThat(result).hasSize(2);
    CohortAssignment newPair =
        result.stream().filter(a -> a.pair().equals("NEWUSDT")).findFirst().orElseThrow();
    assertThat(newPair.adv()).isEqualTo(0.0);
    assertThat(newPair.advRank()).isEqualTo(2);
    assertThat(newPair.cohort()).isEqualTo(Cohort.ALT);
  }

  @Test
  void changingTopKChangesTheAssignment_inertnessCheck() {
    // ADR-0027 D2: a frozen parameter must be proven to change behaviour before it is frozen.
    Map<String, List<Candle>> byPair =
        Map.of(
            "BTCUSDT", dailyCandles(30, 1_000_000),
            "ETHUSDT", dailyCandles(30, 500_000),
            "SOLUSDT", dailyCandles(30, 100_000));
    Instant asOf = DAY0.plus(30, ChronoUnit.DAYS);

    List<CohortAssignment> topK1 = CohortAssigner.assign(byPair, asOf, Duration.ofDays(30), 1);
    List<CohortAssignment> topK2 = CohortAssigner.assign(byPair, asOf, Duration.ofDays(30), 2);

    long majorsAtK1 = topK1.stream().filter(a -> a.cohort() == Cohort.MAJOR).count();
    long majorsAtK2 = topK2.stream().filter(a -> a.cohort() == Cohort.MAJOR).count();
    assertThat(majorsAtK1).isEqualTo(1);
    assertThat(majorsAtK2).isEqualTo(2);
    assertThat(majorsAtK1).isNotEqualTo(majorsAtK2);
  }

  @Test
  void assignPerWindowReassignsIndependentlyPerInstant() {
    // SOL overtakes BTC's volume in the second window -- membership must track it, not freeze.
    List<Candle> btc = new ArrayList<>(dailyCandles(30, 1_000_000));
    btc.addAll(dailyCandles(30, 100_000).stream().map(c -> shifted(c, 30)).toList());
    List<Candle> sol = new ArrayList<>(dailyCandles(30, 100_000));
    sol.addAll(dailyCandles(30, 1_000_000).stream().map(c -> shifted(c, 30)).toList());

    Map<String, List<Candle>> byPair = Map.of("BTCUSDT", btc, "SOLUSDT", sol);
    Instant window1 = DAY0.plus(30, ChronoUnit.DAYS);
    Instant window2 = DAY0.plus(60, ChronoUnit.DAYS);

    Map<Instant, List<CohortAssignment>> result =
        CohortAssigner.assignPerWindow(byPair, List.of(window1, window2), Duration.ofDays(30), 1);

    assertThat(topPair(result.get(window1))).isEqualTo("BTCUSDT");
    assertThat(topPair(result.get(window2))).isEqualTo("SOLUSDT");
  }

  private static String topPair(List<CohortAssignment> assignments) {
    return assignments.stream().filter(a -> a.advRank() == 1).findFirst().orElseThrow().pair();
  }

  private static Candle shifted(Candle c, int days) {
    return new Candle(
        c.openTime().plus(days, ChronoUnit.DAYS),
        c.open(),
        c.high(),
        c.low(),
        c.close(),
        c.volume());
  }

  @Test
  void rejectsEmptyCandlesBySymbol() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CohortAssigner.assign(Map.of(), DAY0, Duration.ofDays(30), 1));
  }

  @Test
  void rejectsNonPositiveLookback() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                CohortAssigner.assign(
                    Map.of("BTCUSDT", dailyCandles(1, 1)), DAY0.plusSeconds(1), Duration.ZERO, 1));
  }

  @Test
  void rejectsTopKOutOfRange() {
    Map<String, List<Candle>> byPair = Map.of("BTCUSDT", dailyCandles(1, 1));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> CohortAssigner.assign(byPair, DAY0.plusSeconds(1), Duration.ofDays(1), 2));
  }
}
