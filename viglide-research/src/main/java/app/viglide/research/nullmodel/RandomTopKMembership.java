package app.viglide.research.nullmodel;

import app.viglide.core.domain.FundingEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The matched-turnover random-top-k baseline that ADR-0016 condition 6 measures a cross-sectional
 * strategy against — lifted out of {@code viglide-strategies/src/test} into main source by PLAN-023
 * Task B, where it had been a package-private test fixture reachable only from JUnit spike classes
 * even though PLAN-016's pre-registration names it by symbol.
 *
 * <p><strong>Deliberately knows nothing about any strategy.</strong> It needs only the funding
 * series (to decide who is eligible and when), {@code k}, and {@code minFundingEvents}. That is why
 * it can live in the public repository at all while the strategy it is a baseline for stays
 * private: "hold a random k of the carry-eligible universe" is not a carry-ranking idea, it is the
 * absence of one.
 *
 * <p><strong>Why turnover has to be matched, which is the whole point of the qualifier.</strong>
 * The question this baseline answers is narrow: does ranking by carry <em>specifically</em> beat
 * holding a diversified basket of the same eligible pairs? A naive random baseline that re-draws
 * its whole book every period would churn far more than the ranked book and be buried by fees, and
 * the comparison would then be measuring turnover rather than ranking. So this applies the same
 * low-turnover discipline: a held symbol is dropped <em>only</em> when it stops being eligible, and
 * empty slots are refilled at random. That matters acutely against a book whose measured problem is
 * that cost dominates signal — an unmatched baseline would flatter the strategy by losing to fees
 * even harder.
 */
public final class RandomTopKMembership {

  private RandomTopKMembership() {}

  /**
   * Membership series per symbol: at each funding instant, whether that symbol is held.
   *
   * <p>Deterministic in {@code seed} (NFR-7): the same seed over the same funding series yields the
   * same membership, because eligibility is derived in sorted-symbol order and the refill draw is a
   * single {@link Collections#shuffle} against one seeded {@link Random}.
   *
   * @param fundingBySymbol every symbol's funding events, ascending by time
   * @param k book size — how many symbols are held at once
   * @param minFundingEvents warmup floor; a symbol with fewer realised events so far is not
   *     eligible, matching the ranked strategy's own exclusion rule rather than scoring it as zero
   * @param seed permutation seed
   */
  public static Map<String, NavigableMap<Instant, Boolean>> compute(
      Map<String, List<FundingEvent>> fundingBySymbol, int k, int minFundingEvents, long seed) {
    Objects.requireNonNull(fundingBySymbol, "fundingBySymbol");
    if (k < 1) {
      throw new IllegalArgumentException("k must be >= 1, got: " + k);
    }
    if (minFundingEvents < 0) {
      throw new IllegalArgumentException("minFundingEvents must be >= 0, got: " + minFundingEvents);
    }

    TreeSet<Instant> allTimes = new TreeSet<>();
    for (List<FundingEvent> events : fundingBySymbol.values()) {
      for (FundingEvent e : events) {
        allTimes.add(e.time());
      }
    }
    Random random = new Random(seed);

    // Sorted, not the caller's iteration order: eligibility and the refill candidate list are both
    // built by walking symbols, so an unsorted walk would make the shuffle seed-stable but
    // input-order-sensitive -- deterministic only by accident.
    TreeSet<String> symbols = new TreeSet<>(fundingBySymbol.keySet());

    Map<String, NavigableMap<Instant, Boolean>> result = new HashMap<>();
    Map<String, Integer> idx = new HashMap<>();
    for (String symbol : symbols) {
      result.put(symbol, new TreeMap<>());
      idx.put(symbol, 0);
    }

    Set<String> held = new HashSet<>();
    for (Instant t : allTimes) {
      List<String> eligible = new ArrayList<>();
      for (String symbol : symbols) {
        List<FundingEvent> events = fundingBySymbol.get(symbol);
        int i = idx.get(symbol);
        while (i < events.size() && !events.get(i).time().isAfter(t)) {
          i++;
        }
        idx.put(symbol, i);
        if (i >= minFundingEvents) {
          eligible.add(symbol);
        }
      }
      held.retainAll(Set.copyOf(eligible)); // drop only on losing eligibility -- matched turnover
      List<String> refillPool = new ArrayList<>(eligible);
      refillPool.removeAll(held);
      Collections.shuffle(refillPool, random);
      int need = k - held.size();
      if (need > 0) {
        held.addAll(refillPool.subList(0, Math.min(need, refillPool.size())));
      }
      Set<String> selected = Set.copyOf(held);
      for (String symbol : symbols) {
        result.get(symbol).put(t, selected.contains(symbol));
      }
    }
    return result;
  }
}
