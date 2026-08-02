package app.viglide.core.regime;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.FundingEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** PLAN-009 Task H: deterministic labeling on injected fixture data (no real market data). */
class RegimeLabelerTest {

  private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

  @Test
  void labelMonthly_ordersVolatilityRegimesByAmplitude() {
    // Six months, amplitude strictly increasing month over month -> the lowest-amplitude months
    // must land LOW and the highest-amplitude months must land HIGH, regardless of exact tercile
    // cut points.
    List<Candle> candles = new ArrayList<>();
    double[] amplitudes = {0.0001, 0.0002, 0.01, 0.012, 0.05, 0.06};
    for (int m = 0; m < 6; m++) {
      YearMonth ym = YearMonth.of(2024, m + 1);
      candles.addAll(hourlyCandlesForMonth(ym, amplitudes[m]));
    }

    List<MonthlyRegime> labels = RegimeLabeler.labelMonthly(candles, List.of());

    assertThat(labels).hasSize(6);
    assertThat(labels.get(0).volatility()).isEqualTo(VolatilityRegime.LOW);
    assertThat(labels.get(1).volatility()).isEqualTo(VolatilityRegime.LOW);
    assertThat(labels.get(4).volatility()).isEqualTo(VolatilityRegime.HIGH);
    assertThat(labels.get(5).volatility()).isEqualTo(VolatilityRegime.HIGH);
    // Every funding regime is UNKNOWN -- no funding events were supplied.
    assertThat(labels).allSatisfy(l -> assertThat(l.funding()).isEqualTo(FundingRegime.UNKNOWN));
  }

  @Test
  void labelMonthly_splitsFundingRichAndCompressedAroundTheFullHistoryMedian() {
    List<Candle> candles = new ArrayList<>();
    List<FundingEvent> funding = new ArrayList<>();
    // 3 "compressed" months (small |rate|), 3 "rich" months (large |rate|) -- the full-history
    // median falls between the two groups, so every month should cleanly land on one side.
    String[] pattern = {"compressed", "compressed", "compressed", "rich", "rich", "rich"};
    for (int m = 0; m < 6; m++) {
      YearMonth ym = YearMonth.of(2024, m + 1);
      candles.addAll(hourlyCandlesForMonth(ym, 0.01));
      BigDecimal rate =
          "rich".equals(pattern[m]) ? new BigDecimal("0.005") : new BigDecimal("0.0001");
      funding.addAll(fundingEventsForMonth(ym, rate));
    }

    List<MonthlyRegime> labels = RegimeLabeler.labelMonthly(candles, funding);

    assertThat(labels).hasSize(6);
    for (int m = 0; m < 3; m++) {
      assertThat(labels.get(m).funding()).isEqualTo(FundingRegime.COMPRESSED);
    }
    for (int m = 3; m < 6; m++) {
      assertThat(labels.get(m).funding()).isEqualTo(FundingRegime.RICH);
    }
  }

  @Test
  void labelMonthly_tooFewFundingEventsInTrailingWindow_isUnknownNotGuessed() {
    List<Candle> candles = new ArrayList<>(hourlyCandlesForMonth(YearMonth.of(2024, 1), 0.01));
    // Only 1 funding event this month -- below MIN_FUNDING_EVENTS_FOR_LABEL (3).
    List<FundingEvent> funding =
        List.of(new FundingEvent(START.plusSeconds(3600), new BigDecimal("0.001")));

    List<MonthlyRegime> labels = RegimeLabeler.labelMonthly(candles, funding);

    assertThat(labels).hasSize(1);
    assertThat(labels.get(0).funding()).isEqualTo(FundingRegime.UNKNOWN);
  }

  @Test
  void labelMonthly_emptyCandles_returnsEmptyList() {
    assertThat(RegimeLabeler.labelMonthly(List.of(), List.of())).isEmpty();
  }

  @Test
  void labelMonthly_asOfIsTheMonthsLastCandleOpenTime() {
    List<Candle> candles = new ArrayList<>(hourlyCandlesForMonth(YearMonth.of(2024, 1), 0.01));
    Instant expectedLast = candles.get(candles.size() - 1).openTime();

    List<MonthlyRegime> labels = RegimeLabeler.labelMonthly(candles, List.of());

    assertThat(labels.get(0).asOf()).isEqualTo(expectedLast);
    assertThat(labels.get(0).month()).isEqualTo(YearMonth.of(2024, 1));
  }

  @Test
  void computeReference_thenClassify_agreesWithLabelMonthlyOnTheSameData() {
    // PLAN-009 Task H item 3: the live/streaming path (computeReference + classify*) must agree
    // with the batch path (labelMonthly) on the same historical data -- same trailing windows,
    // same source numbers, just exposed as fixed thresholds instead of a per-batch rank.
    List<Candle> candles = new ArrayList<>();
    List<FundingEvent> funding = new ArrayList<>();
    double[] amplitudes = {0.0001, 0.0002, 0.01, 0.012, 0.05, 0.06};
    String[] pattern = {"compressed", "compressed", "compressed", "rich", "rich", "rich"};
    for (int m = 0; m < 6; m++) {
      YearMonth ym = YearMonth.of(2024, m + 1);
      candles.addAll(hourlyCandlesForMonth(ym, amplitudes[m]));
      BigDecimal rate =
          "rich".equals(pattern[m]) ? new BigDecimal("0.005") : new BigDecimal("0.0001");
      funding.addAll(fundingEventsForMonth(ym, rate));
    }

    List<MonthlyRegime> batch = RegimeLabeler.labelMonthly(candles, funding);
    RegimeReference reference = RegimeLabeler.computeReference(candles, funding);

    // Re-derive each month's own trailing readings the same way labelMonthly does internally,
    // then classify them against the reference -- must match the batch labels exactly.
    for (int m = 0; m < 6; m++) {
      YearMonth ym = YearMonth.of(2024, m + 1);
      Instant asOf = batch.get(m).asOf();
      // Boundary convention must match RegimeLabeler's own trailing-window filters exactly
      // (openTime strictly after `from`, not after `asOf`) -- an inclusive "from" here would
      // silently include one extra boundary candle/event and diverge from the batch path.
      Instant from = asOf.minus(java.time.Duration.ofDays(30));
      List<Candle> trailingCandles =
          candles.stream()
              .filter(c -> c.openTime().isAfter(from))
              .filter(c -> !c.openTime().isAfter(asOf))
              .toList();
      List<FundingEvent> trailingFunding =
          funding.stream()
              .filter(f -> f.time().isAfter(from))
              .filter(f -> !f.time().isAfter(asOf))
              .toList();

      FundingRegime liveFunding =
          RegimeLabeler.classifyFunding(
              RegimeLabeler.trailingMedianAbsFundingRate(trailingFunding), reference);
      VolatilityRegime liveVol =
          RegimeLabeler.classifyVolatility(
              RegimeLabeler.trailingRealizedVolatility(trailingCandles), reference);

      assertThat(liveFunding).as("month %s funding", ym).isEqualTo(batch.get(m).funding());
      assertThat(liveVol).as("month %s volatility", ym).isEqualTo(batch.get(m).volatility());
    }
  }

  @Test
  void classifyFunding_missingReference_isUnknown() {
    RegimeReference reference = new RegimeReference(Optional.empty(), 0.0, 0.01);
    assertThat(RegimeLabeler.classifyFunding(Optional.of(new BigDecimal("0.001")), reference))
        .isEqualTo(FundingRegime.UNKNOWN);
  }

  @Test
  void classifyVolatility_missingTrailingReading_isUnknown() {
    RegimeReference reference = new RegimeReference(Optional.empty(), 0.0, 0.01);
    assertThat(RegimeLabeler.classifyVolatility(Optional.empty(), reference))
        .isEqualTo(VolatilityRegime.UNKNOWN);
  }

  // ── fixture builders ────────────────────────────────────────────────────────────────────────

  /** Hourly candles spanning {@code month}, oscillating +/-{@code amplitude} around 100. */
  private static List<Candle> hourlyCandlesForMonth(YearMonth month, double amplitude) {
    Instant monthStart = month.atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    int hours = month.lengthOfMonth() * 24;
    List<Candle> out = new ArrayList<>(hours);
    double base = 100.0;
    for (int h = 0; h < hours; h++) {
      double price = base * (1 + amplitude * Math.sin(h * 0.7));
      BigDecimal close = BigDecimal.valueOf(price);
      out.add(
          new Candle(
              monthStart.plusSeconds(h * 3600L),
              close,
              close.add(BigDecimal.ONE),
              close.subtract(BigDecimal.ONE).max(BigDecimal.valueOf(0.01)),
              close,
              BigDecimal.valueOf(1000)));
    }
    return out;
  }

  /** Funding events every 8h through {@code month}, all at the given (signed) rate. */
  private static List<FundingEvent> fundingEventsForMonth(YearMonth month, BigDecimal rate) {
    Instant monthStart = month.atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    int events = month.lengthOfMonth() * 3; // every 8h
    List<FundingEvent> out = new ArrayList<>(events);
    for (int i = 0; i < events; i++) {
      out.add(new FundingEvent(monthStart.plusSeconds(i * 8 * 3600L), rate));
    }
    return out;
  }
}
