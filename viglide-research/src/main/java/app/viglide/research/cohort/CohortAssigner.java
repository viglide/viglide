package app.viglide.research.cohort;

import app.viglide.core.domain.Candle;
import app.viglide.core.indicator.IndicatorMath;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Assigns each pair in a universe to {@link Cohort#MAJOR} or {@link Cohort#ALT} by an ex-ante,
 * observable rule — average daily dollar volume (ADV) ranked over a trailing lookback window that
 * ends strictly before the evaluation instant — never by which grouping happens to score better
 * downstream (ADR-0028 S5, PLAN-024 §2.4).
 *
 * <p>ADV, not market-cap rank, listing age, or realised-volatility percentile: it is the one of
 * ADR-0028's four blessed criteria computable directly from this project's existing OHLCV corpus,
 * with no external data source and therefore no point-in-time-vintage risk (E1) to manage. {@code
 * close * volume}, summed over the lookback window and divided by its length in days, using
 * whatever candle interval the caller supplies — one consistent interval across every pair in a
 * call is the only requirement, not any particular bar size.
 */
public final class CohortAssigner {

  private CohortAssigner() {}

  private record PairAdv(String pair, double adv, int candlesInWindow) {}

  /**
   * @param candlesBySymbol every pair's own candle history, ascending by {@code openTime}, one
   *     consistent interval across every pair
   * @param asOf the evaluation instant — only candles with {@code openTime < asOf} are read, so a
   *     later run can never see this call's own future (the point-in-time guarantee S5 requires)
   * @param lookback how far back from {@code asOf} to compute average daily dollar volume
   * @param topKMajors how many of the highest-ADV pairs are assigned {@link Cohort#MAJOR}; the rest
   *     are {@link Cohort#ALT}
   * @return one {@link CohortAssignment} per key of {@code candlesBySymbol}, ranked by ADV
   *     descending (rank 1 = highest); a pair with no candles in the lookback window gets ADV 0 and
   *     ranks last, never excluded. When <em>every</em> pair is empty — the corpus's first window,
   *     whose lookback predates the first bar — the ranking is decided entirely by the alphabetical
   *     tie-break and carries no liquidity information. That case is not thrown on, because "no
   *     history yet" is a corpus boundary rather than a caller error, but each assignment reports
   *     {@link CohortAssignment#degenerate()} so a caller can refuse to score it.
   */
  public static List<CohortAssignment> assign(
      Map<String, List<Candle>> candlesBySymbol, Instant asOf, Duration lookback, int topKMajors) {
    if (candlesBySymbol == null || candlesBySymbol.isEmpty()) {
      throw new IllegalArgumentException("candlesBySymbol must not be empty");
    }
    if (lookback.isZero() || lookback.isNegative()) {
      throw new IllegalArgumentException("lookback must be positive, got: " + lookback);
    }
    if (topKMajors < 0 || topKMajors > candlesBySymbol.size()) {
      throw new IllegalArgumentException(
          "topKMajors must be in [0, " + candlesBySymbol.size() + "], got: " + topKMajors);
    }
    Instant windowStart = asOf.minus(lookback);
    long lookbackDays = Math.max(1, lookback.toDays());

    List<PairAdv> ranked = new ArrayList<>();
    for (Map.Entry<String, List<Candle>> entry : new TreeMap<>(candlesBySymbol).entrySet()) {
      BigDecimal dollarVolume = BigDecimal.ZERO;
      int candlesInWindow = 0;
      for (Candle c : entry.getValue()) {
        if (!c.openTime().isBefore(windowStart) && c.openTime().isBefore(asOf)) {
          dollarVolume =
              dollarVolume.add(c.close().multiply(c.volume(), IndicatorMath.MC), IndicatorMath.MC);
          candlesInWindow++;
        }
      }
      double adv =
          dollarVolume.divide(BigDecimal.valueOf(lookbackDays), IndicatorMath.MC).doubleValue();
      ranked.add(new PairAdv(entry.getKey(), adv, candlesInWindow));
    }
    ranked.sort(
        Comparator.<PairAdv>comparingDouble(PairAdv::adv).reversed().thenComparing(PairAdv::pair));

    List<CohortAssignment> out = new ArrayList<>(ranked.size());
    for (int i = 0; i < ranked.size(); i++) {
      int rank = i + 1;
      Cohort cohort = rank <= topKMajors ? Cohort.MAJOR : Cohort.ALT;
      out.add(
          new CohortAssignment(
              ranked.get(i).pair(),
              cohort,
              asOf,
              ranked.get(i).adv(),
              rank,
              ranked.get(i).candlesInWindow()));
    }
    return List.copyOf(out);
  }

  /**
   * {@link #assign}, repeated once per {@code evaluationInstants} entry (PLAN-024 §2.4: "evaluated
   * at the start of each out-of-sample window") — a pair's cohort can drift across windows as its
   * own ADV rank changes, which is the point: membership is re-derived every window, never frozen
   * once and reused.
   */
  public static Map<Instant, List<CohortAssignment>> assignPerWindow(
      Map<String, List<Candle>> candlesBySymbol,
      List<Instant> evaluationInstants,
      Duration lookback,
      int topKMajors) {
    Map<Instant, List<CohortAssignment>> out = new LinkedHashMap<>();
    for (Instant asOf : evaluationInstants) {
      out.put(asOf, assign(candlesBySymbol, asOf, lookback, topKMajors));
    }
    return Collections.unmodifiableMap(out);
  }
}
