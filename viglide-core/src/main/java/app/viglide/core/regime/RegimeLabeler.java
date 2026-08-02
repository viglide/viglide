package app.viglide.core.regime;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.FundingEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Labels each calendar month a pair traded with a funding regime and a volatility regime (PLAN-009
 * Task H). Deterministic and side-effect free (CLAUDE.md §4/§5): every label is derived only from
 * the candles/funding events handed in, never wall-clock time or I/O.
 *
 * <p>Both regimes are relative to the pair's <em>own</em> full-history distribution — "rich"
 * funding or "high" volatility for BTC is a different absolute number than for a low-cap altcoin,
 * so labels are never comparable across pairs, only across time for the same pair.
 */
public final class RegimeLabeler {

  /** Below this many hourly candles in the trailing 30d window, volatility is {@code UNKNOWN}. */
  static final int MIN_CANDLES_FOR_LABEL = 48; // 2 days — enough for a non-degenerate stdev

  /**
   * Below this many funding events in the trailing 30d window, funding regime is {@code UNKNOWN}.
   */
  static final int MIN_FUNDING_EVENTS_FOR_LABEL = 3;

  private static final Duration TRAILING_WINDOW = Duration.ofDays(30);

  private RegimeLabeler() {}

  /**
   * Labels every calendar month present in {@code hourlyCandles} (UTC month boundaries). Each
   * month's label is "as of" that month's last candle: the trailing-30d window ending there feeds
   * both regimes. Volatility terciles are computed once, across every month's trailing-30d reading
   * for this pair — a month with too few trailing candles/funding events is {@code UNKNOWN} rather
   * than guessed, and is excluded from the tercile-boundary computation itself so a handful of thin
   * early months don't skew where the boundaries fall for the rest.
   *
   * @param hourlyCandles ascending {@code openTime}, one-hour interval (Task H's spec: "from 1h
   *     closes") — not re-validated for interval here, callers are expected to pass an hourly
   *     series
   * @param fundingEvents ascending {@code time}; may be empty (funding regime is {@code UNKNOWN}
   *     for every month in that case)
   */
  public static List<MonthlyRegime> labelMonthly(
      List<Candle> hourlyCandles, List<FundingEvent> fundingEvents) {
    Objects.requireNonNull(hourlyCandles, "hourlyCandles");
    Objects.requireNonNull(fundingEvents, "fundingEvents");
    if (hourlyCandles.isEmpty()) return List.of();

    List<YearMonth> months = distinctMonths(hourlyCandles);
    List<Instant> asOfPerMonth = new ArrayList<>(months.size());
    List<Double> trailingVolPerMonth = new ArrayList<>(months.size());
    List<FundingRegime> fundingPerMonth = new ArrayList<>(months.size());

    BigDecimal fullHistoryMedianAbsRate = medianAbsRate(fundingEvents);

    for (YearMonth month : months) {
      Instant asOf = lastCandleOpenTimeInMonth(hourlyCandles, month);
      asOfPerMonth.add(asOf);

      List<Candle> trailing = candlesInTrailingWindow(hourlyCandles, asOf);
      trailingVolPerMonth.add(
          trailing.size() < MIN_CANDLES_FOR_LABEL ? null : realizedVol(trailing));

      List<FundingEvent> trailingFunding = fundingInTrailingWindow(fundingEvents, asOf);
      if (trailingFunding.size() < MIN_FUNDING_EVENTS_FOR_LABEL
          || fullHistoryMedianAbsRate == null) {
        fundingPerMonth.add(FundingRegime.UNKNOWN);
      } else {
        BigDecimal trailingMedian = medianAbsRate(trailingFunding);
        fundingPerMonth.add(
            trailingMedian.compareTo(fullHistoryMedianAbsRate) > 0
                ? FundingRegime.RICH
                : FundingRegime.COMPRESSED);
      }
    }

    List<VolatilityRegime> volRegimes = bucketByRank(trailingVolPerMonth);

    List<MonthlyRegime> out = new ArrayList<>(months.size());
    for (int i = 0; i < months.size(); i++) {
      out.add(
          new MonthlyRegime(
              months.get(i), asOfPerMonth.get(i), fundingPerMonth.get(i), volRegimes.get(i)));
    }
    return out;
  }

  /**
   * Computes the reference statistics a live decision loop classifies each new bar against
   * (PLAN-009 Task H item 3): the same full-history median {@code |funding rate|} and volatility
   * tercile boundaries {@link #labelMonthly} uses internally, exposed once as fixed thresholds
   * instead of a per-batch rank. Call this offline against the full historical corpus at startup —
   * never on the hot path.
   */
  public static RegimeReference computeReference(
      List<Candle> hourlyCandles, List<FundingEvent> fundingEvents) {
    Objects.requireNonNull(hourlyCandles, "hourlyCandles");
    Objects.requireNonNull(fundingEvents, "fundingEvents");

    BigDecimal fullHistoryMedian = medianAbsRate(fundingEvents);

    List<Double> monthlyVol = new ArrayList<>();
    for (YearMonth month : distinctMonths(hourlyCandles)) {
      Instant asOf = lastCandleOpenTimeInMonth(hourlyCandles, month);
      List<Candle> trailing = candlesInTrailingWindow(hourlyCandles, asOf);
      if (trailing.size() >= MIN_CANDLES_FOR_LABEL) monthlyVol.add(realizedVol(trailing));
    }
    monthlyVol.sort(Double::compareTo);
    double p33 = 0.0;
    double p66 = 0.0;
    int n = monthlyVol.size();
    if (n >= 3) {
      // Threshold = the LAST value that {@link #bucketByRank} would place in the lower group, not
      // the first value of the next group — an inclusive "value <= threshold" comparison then
      // reproduces bucketByRank's rank-based split exactly (see classifyVolatility), instead of
      // silently shifting the boundary point into the wrong bucket the way a naive p33/p66-index
      // read would (the same off-by-one bucketByRank itself was fixed for, PLAN-009 Task H).
      int lowGroupSize = (int) Math.floor(n / 3.0);
      int mediumGroupEnd = (int) Math.floor(n * 2.0 / 3.0);
      p33 = monthlyVol.get(lowGroupSize - 1);
      p66 = monthlyVol.get(mediumGroupEnd - 1);
    }

    return new RegimeReference(Optional.ofNullable(fullHistoryMedian), p33, p66);
  }

  /**
   * Classifies one new trailing-30d {@code |funding rate|} median against a precomputed {@link
   * RegimeReference} (PLAN-009 Task H item 3 — live, streaming use; {@link #labelMonthly} is the
   * batch equivalent). {@code UNKNOWN} when either input is absent.
   */
  public static FundingRegime classifyFunding(
      Optional<BigDecimal> trailingMedianAbsRate, RegimeReference reference) {
    Objects.requireNonNull(trailingMedianAbsRate, "trailingMedianAbsRate");
    Objects.requireNonNull(reference, "reference");
    if (trailingMedianAbsRate.isEmpty() || reference.fullHistoryMedianAbsFundingRate().isEmpty()) {
      return FundingRegime.UNKNOWN;
    }
    return trailingMedianAbsRate.get().compareTo(reference.fullHistoryMedianAbsFundingRate().get())
            > 0
        ? FundingRegime.RICH
        : FundingRegime.COMPRESSED;
  }

  /**
   * Classifies one new trailing-30d realized-vol reading against a precomputed {@link
   * RegimeReference}'s tercile boundaries (PLAN-009 Task H item 3 — live, streaming use).
   */
  public static VolatilityRegime classifyVolatility(
      Optional<Double> trailingVol, RegimeReference reference) {
    Objects.requireNonNull(trailingVol, "trailingVol");
    Objects.requireNonNull(reference, "reference");
    if (trailingVol.isEmpty()) return VolatilityRegime.UNKNOWN;
    double v = trailingVol.get();
    if (v <= reference.volTercileP33()) return VolatilityRegime.LOW;
    if (v <= reference.volTercileP66()) return VolatilityRegime.MEDIUM;
    return VolatilityRegime.HIGH;
  }

  /**
   * Public wrapper over the trailing-window realized-vol computation ({@link #realizedVol}) for a
   * live loop that maintains its own trailing-30d candle buffer — {@code UNKNOWN}/empty when
   * there's fewer than {@link #MIN_CANDLES_FOR_LABEL} candles to compute from.
   */
  public static Optional<Double> trailingRealizedVolatility(List<Candle> trailingCandles) {
    Objects.requireNonNull(trailingCandles, "trailingCandles");
    return trailingCandles.size() < MIN_CANDLES_FOR_LABEL
        ? Optional.empty()
        : Optional.of(realizedVol(trailingCandles));
  }

  /**
   * Public wrapper over the trailing-window median {@code |funding rate|} computation ({@link
   * #medianAbsRate}) for a live loop that maintains its own trailing-30d funding-event buffer —
   * empty when there's fewer than {@link #MIN_FUNDING_EVENTS_FOR_LABEL} events to compute from.
   */
  public static Optional<BigDecimal> trailingMedianAbsFundingRate(
      List<FundingEvent> trailingEvents) {
    Objects.requireNonNull(trailingEvents, "trailingEvents");
    return trailingEvents.size() < MIN_FUNDING_EVENTS_FOR_LABEL
        ? Optional.empty()
        : Optional.ofNullable(medianAbsRate(trailingEvents));
  }

  private static List<YearMonth> distinctMonths(List<Candle> candles) {
    List<YearMonth> months = new ArrayList<>();
    YearMonth last = null;
    for (Candle c : candles) {
      YearMonth ym = YearMonth.from(c.openTime().atZone(ZoneOffset.UTC));
      if (!ym.equals(last)) {
        months.add(ym);
        last = ym;
      }
    }
    return months;
  }

  private static Instant lastCandleOpenTimeInMonth(List<Candle> candles, YearMonth month) {
    Instant last = null;
    for (Candle c : candles) {
      YearMonth ym = YearMonth.from(c.openTime().atZone(ZoneOffset.UTC));
      if (ym.equals(month)) last = c.openTime();
      else if (last != null) break; // months are contiguous in an ascending series
    }
    return last;
  }

  private static List<Candle> candlesInTrailingWindow(List<Candle> candles, Instant asOf) {
    Instant from = asOf.minus(TRAILING_WINDOW);
    List<Candle> out = new ArrayList<>();
    for (Candle c : candles) {
      if (c.openTime().isAfter(from) && !c.openTime().isAfter(asOf)) out.add(c);
    }
    return out;
  }

  private static List<FundingEvent> fundingInTrailingWindow(
      List<FundingEvent> events, Instant asOf) {
    Instant from = asOf.minus(TRAILING_WINDOW);
    List<FundingEvent> out = new ArrayList<>();
    for (FundingEvent f : events) {
      if (f.time().isAfter(from) && !f.time().isAfter(asOf)) out.add(f);
    }
    return out;
  }

  /** Sample stdev of consecutive-candle log returns — the "realized volatility" reading. */
  private static double realizedVol(List<Candle> candles) {
    double[] returns = new double[candles.size() - 1];
    for (int i = 1; i < candles.size(); i++) {
      double prev = candles.get(i - 1).close().doubleValue();
      double curr = candles.get(i).close().doubleValue();
      returns[i - 1] = Math.log(curr / prev);
    }
    double mean = Arrays.stream(returns).average().orElse(0.0);
    double sumSq = 0.0;
    for (double r : returns) sumSq += (r - mean) * (r - mean);
    return returns.length < 2 ? 0.0 : Math.sqrt(sumSq / (returns.length - 1));
  }

  private static BigDecimal medianAbsRate(List<FundingEvent> events) {
    if (events.isEmpty()) return null;
    List<BigDecimal> abs = new ArrayList<>(events.size());
    for (FundingEvent f : events) abs.add(f.rate().abs());
    abs.sort(BigDecimal::compareTo);
    int n = abs.size();
    return n % 2 == 1
        ? abs.get(n / 2)
        : abs.get(n / 2 - 1).add(abs.get(n / 2)).divide(BigDecimal.valueOf(2));
  }

  /**
   * Buckets each reading into a tercile by its <em>rank</em> among the non-null readings, not by
   * comparing against a cut value — a value-threshold split (e.g. {@code value <= p66}) always
   * pulls the boundary point itself into the lower bucket, silently skewing an even split (e.g. 6
   * sorted readings would go 3/2/1 instead of 2/2/2). Rank-based bucketing gives the conventional
   * "bottom/middle/top third" split for any {@code N}, ties broken by original list order. {@code
   * null} readings (too little trailing history to compute, per {@link #MIN_CANDLES_FOR_LABEL}) map
   * to {@link VolatilityRegime#UNKNOWN} and never occupy a tercile slot.
   */
  private static List<VolatilityRegime> bucketByRank(List<Double> readings) {
    List<Integer> presentIndices = new ArrayList<>();
    for (int i = 0; i < readings.size(); i++) {
      if (readings.get(i) != null) presentIndices.add(i);
    }
    VolatilityRegime[] out = new VolatilityRegime[readings.size()];
    Arrays.fill(out, VolatilityRegime.UNKNOWN);
    if (presentIndices.size() < 3) return Arrays.asList(out);

    List<Integer> byValue = new ArrayList<>(presentIndices);
    byValue.sort((a, b) -> Double.compare(readings.get(a), readings.get(b)));
    int n = byValue.size();
    for (int rank = 0; rank < n; rank++) {
      VolatilityRegime regime =
          rank < n / 3.0
              ? VolatilityRegime.LOW
              : (rank < 2 * n / 3.0 ? VolatilityRegime.MEDIUM : VolatilityRegime.HIGH);
      out[byValue.get(rank)] = regime;
    }
    return Arrays.asList(out);
  }
}
