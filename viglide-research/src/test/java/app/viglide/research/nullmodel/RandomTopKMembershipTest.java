package app.viglide.research.nullmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.viglide.core.domain.FundingEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import org.junit.jupiter.api.Test;

/** PLAN-023 Task B: the matched-turnover random-top-k baseline, now in main source. */
class RandomTopKMembershipTest {

  private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");

  @Test
  void holdsExactlyKOnceEnoughSymbolsAreEligible() {
    var membership = RandomTopKMembership.compute(funding(6, 40), 3, 6, 42L);

    // Before warmup nothing is eligible; after it, the book is always exactly k.
    for (Instant t : times(membership)) {
      long held = membership.values().stream().filter(m -> Boolean.TRUE.equals(m.get(t))).count();
      assertThat(held).isLessThanOrEqualTo(3);
    }
    Instant last = times(membership).get(times(membership).size() - 1);
    long heldAtEnd =
        membership.values().stream().filter(m -> Boolean.TRUE.equals(m.get(last))).count();
    assertThat(heldAtEnd).isEqualTo(3);
  }

  @Test
  void turnoverIsMatched_aHeldSymbolIsNeverDroppedWhileStillEligible() {
    // This is the property the "matched-turnover" qualifier names, and the reason the baseline is
    // meaningful at all: a baseline that re-drew its book every period would be buried by fees and
    // the comparison would measure churn instead of ranking.
    var membership = RandomTopKMembership.compute(funding(6, 60), 2, 6, 7L);
    List<Instant> ts = times(membership);

    int drops = 0;
    for (int i = 1; i < ts.size(); i++) {
      for (var e : membership.entrySet()) {
        boolean before = Boolean.TRUE.equals(e.getValue().get(ts.get(i - 1)));
        boolean after = Boolean.TRUE.equals(e.getValue().get(ts.get(i)));
        // Every symbol stays eligible for the whole series here, so any drop is unmatched churn.
        if (before && !after) drops++;
      }
    }
    assertThat(drops).isZero();
  }

  @Test
  void isDeterministicInSeed_andDiffersBetweenSeeds() {
    var a = RandomTopKMembership.compute(funding(8, 40), 3, 6, 42L);
    var b = RandomTopKMembership.compute(funding(8, 40), 3, 6, 42L);
    var c = RandomTopKMembership.compute(funding(8, 40), 3, 6, 43L);

    assertThat(a).isEqualTo(b);
    assertThat(a).isNotEqualTo(c);
  }

  @Test
  void isInvariantToTheCallersMapIterationOrder() {
    // Determinism must not depend on how the caller happened to build its map (NFR-7); a
    // seed-stable shuffle over an input-ordered list would be deterministic only by accident.
    Map<String, List<FundingEvent>> forward = funding(5, 30);
    Map<String, List<FundingEvent>> reversed = new LinkedHashMap<>();
    List<String> keys = new ArrayList<>(forward.keySet());
    for (int i = keys.size() - 1; i >= 0; i--) {
      reversed.put(keys.get(i), forward.get(keys.get(i)));
    }

    assertThat(RandomTopKMembership.compute(reversed, 2, 6, 42L))
        .isEqualTo(RandomTopKMembership.compute(forward, 2, 6, 42L));
  }

  @Test
  void nothingIsHeldBeforeTheWarmupFloorIsMet() {
    var membership = RandomTopKMembership.compute(funding(4, 20), 2, 10, 42L);
    List<Instant> ts = times(membership);

    // minFundingEvents=10 with events every period: no symbol is eligible until the 10th.
    for (int i = 0; i < 9; i++) {
      Instant t = ts.get(i);
      assertThat(membership.values().stream().filter(m -> Boolean.TRUE.equals(m.get(t))).count())
          .as("nothing held at index %d", i)
          .isZero();
    }
  }

  @Test
  void rejectsNonsenseParameters() {
    assertThatThrownBy(() -> RandomTopKMembership.compute(funding(3, 10), 0, 6, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("k must be >= 1");
    assertThatThrownBy(() -> RandomTopKMembership.compute(funding(3, 10), 2, -1, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("minFundingEvents");
  }

  private static List<Instant> times(Map<String, NavigableMap<Instant, Boolean>> m) {
    return new ArrayList<>(m.values().iterator().next().keySet());
  }

  private static Map<String, List<FundingEvent>> funding(int symbols, int events) {
    Map<String, List<FundingEvent>> out = new LinkedHashMap<>();
    for (int s = 0; s < symbols; s++) {
      List<FundingEvent> es = new ArrayList<>();
      for (int i = 0; i < events; i++) {
        es.add(new FundingEvent(T0.plusSeconds(8L * 3600 * i), new BigDecimal("0.0001")));
      }
      out.put("SYM" + (char) ('A' + s), es);
    }
    return out;
  }
}
