package app.viglide.research.cli;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.domain.FundingEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link NullModelCli} (PLAN-013 Task I, review finding F15). {@link
 * NullModelCli#percentileOf} and {@link NullModelCli#circularPermute} carry the statistical content
 * and are tested directly as pure functions. The end-to-end {@code run(...)} tests, which exercise
 * the real CSV-to-harness-to-permutation-loop wiring against {@code --strategy=fundingarb}, live in
 * {@code NullModelCliFundingArbTest} (private repo, PLAN-018 R-5).
 */
class NullModelCliTest {

  private static final Instant T0 = Instant.parse("2023-01-01T00:00:00Z");
  private static final Offset<Double> TOL = Offset.offset(1e-12);

  // ── percentileOf: the mid-rank tie-corrected percentile ─────────────────────────────────────

  @Test
  void percentileOf_allNullsTiedWithReal_isExactlyHalf() {
    double[] nulls = {0.0, 0.0, 0.0, 0.0, 0.0};
    NullModelCli.PercentileResult r = NullModelCli.percentileOf(nulls, 0.0);
    assertThat(r.countStrictlyBelow()).isZero();
    assertThat(r.countEqual()).isEqualTo(5);
    assertThat(r.percentile()).isEqualTo(0.5);
  }

  @Test
  void percentileOf_realBeatsEveryNull_isOne() {
    double[] nulls = {-0.1, -0.05, -0.02, -0.01};
    NullModelCli.PercentileResult r = NullModelCli.percentileOf(nulls, 0.5);
    assertThat(r.countStrictlyBelow()).isEqualTo(4);
    assertThat(r.countEqual()).isZero();
    assertThat(r.percentile()).isEqualTo(1.0);
  }

  @Test
  void percentileOf_realBelowEveryNull_isZero() {
    double[] nulls = {0.1, 0.2, 0.3, 0.4};
    NullModelCli.PercentileResult r = NullModelCli.percentileOf(nulls, -0.5);
    assertThat(r.countStrictlyBelow()).isZero();
    assertThat(r.countEqual()).isZero();
    assertThat(r.percentile()).isEqualTo(0.0);
  }

  @Test
  void percentileOf_mixedBelowTiedAbove_matchesHandComputation() {
    // 2 strictly below, 1 tied, 3 strictly above real=0.0 -> (2 + 0.5*1)/6.
    double[] nulls = {-0.2, -0.1, 0.0, 0.1, 0.2, 0.3};
    NullModelCli.PercentileResult r = NullModelCli.percentileOf(nulls, 0.0);
    assertThat(r.countStrictlyBelow()).isEqualTo(2);
    assertThat(r.countEqual()).isEqualTo(1);
    assertThat(r.percentile()).isCloseTo(2.5 / 6.0, TOL);
  }

  @Test
  void percentileOf_emptyNullDistribution_defaultsToHalf() {
    NullModelCli.PercentileResult r = NullModelCli.percentileOf(new double[0], 1.23);
    assertThat(r.countStrictlyBelow()).isZero();
    assertThat(r.countEqual()).isZero();
    assertThat(r.percentile()).isEqualTo(0.5);
  }

  // ── circularPermute: rotates rates, keeps timestamps fixed ──────────────────────────────────

  @Test
  void circularPermute_offsetZero_isIdentity() {
    List<FundingEvent> original = fundingSequence(1, 2, 3, 4);
    List<FundingEvent> out = NullModelCli.circularPermute(original, 0);
    assertThat(rates(out)).containsExactly(bd(1), bd(2), bd(3), bd(4));
    assertThat(times(out)).isEqualTo(times(original));
  }

  @Test
  void circularPermute_offsetOne_shiftsLeftWithWraparound() {
    List<FundingEvent> original = fundingSequence(1, 2, 3, 4);
    List<FundingEvent> out = NullModelCli.circularPermute(original, 1);
    assertThat(rates(out)).containsExactly(bd(2), bd(3), bd(4), bd(1));
    // Timestamps never move -- only the rate assigned to each timestamp changes.
    assertThat(times(out)).isEqualTo(times(original));
  }

  @Test
  void circularPermute_fullCircleOffset_isIdentity() {
    List<FundingEvent> original = fundingSequence(1, 2, 3, 4);
    List<FundingEvent> out = NullModelCli.circularPermute(original, 4);
    assertThat(rates(out)).containsExactly(bd(1), bd(2), bd(3), bd(4));
  }

  @Test
  void circularPermute_negativeOffset_wrapsCorrectly() {
    List<FundingEvent> original = fundingSequence(1, 2, 3, 4);
    List<FundingEvent> out = NullModelCli.circularPermute(original, -1);
    assertThat(rates(out)).containsExactly(bd(4), bd(1), bd(2), bd(3));
  }

  @Test
  void circularPermute_emptyList_returnsEmpty() {
    assertThat(NullModelCli.circularPermute(List.of(), 5)).isEmpty();
  }

  @Test
  void circularPermute_singleElement_isAlwaysIdentity() {
    List<FundingEvent> original = fundingSequence(7);
    assertThat(rates(NullModelCli.circularPermute(original, 0))).containsExactly(bd(7));
    assertThat(rates(NullModelCli.circularPermute(original, 3))).containsExactly(bd(7));
  }

  // ── asciiHistogram: sanity only, no exact-string coupling ───────────────────────────────────

  @Test
  void asciiHistogram_emptyArray_returnsPlaceholder_doesNotCrash() {
    assertThat(NullModelCli.asciiHistogram(new double[0], 0.0)).isEqualTo("(no null samples)");
  }

  @Test
  void asciiHistogram_degenerateAllSameValue_doesNotCrash() {
    double[] nulls = {0.05, 0.05, 0.05};
    String histogram = NullModelCli.asciiHistogram(nulls, 0.05);
    assertThat(histogram).contains("*").contains("(3)");
  }

  @Test
  void asciiHistogram_nonDegenerate_marksRealResultBucket() {
    double[] nulls = {-0.1, -0.05, 0.0, 0.05, 0.1};
    String histogram = NullModelCli.asciiHistogram(nulls, 0.1);
    assertThat(histogram).contains("*");
  }

  // Note: the end-to-end run(...) tests that exercised --strategy=fundingarb moved to
  // NullModelCliFundingArbTest (viglide-strategies, private repo, PLAN-018 R-5) because
  // fundingarb is no longer on viglide-research's test classpath after the public/private split.

  // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

  private static List<FundingEvent> fundingSequence(int... rates) {
    List<FundingEvent> out = new ArrayList<>();
    Instant t = T0;
    for (int r : rates) {
      out.add(new FundingEvent(t, bd(r)));
      t = t.plusSeconds(8 * 3600);
    }
    return out;
  }

  private static List<BigDecimal> rates(List<FundingEvent> events) {
    return events.stream().map(FundingEvent::rate).toList();
  }

  private static List<Instant> times(List<FundingEvent> events) {
    return events.stream().map(FundingEvent::time).toList();
  }

  private static BigDecimal bd(int v) {
    return BigDecimal.valueOf(v);
  }
}
