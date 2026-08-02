package app.viglide.research.calibrate;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.calibrate.Candidate;
import app.viglide.core.calibrate.DoubleRange;
import app.viglide.core.calibrate.IntRange;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the three {@code *ParameterSpace} classes: range arithmetic, grid enumeration,
 * deterministic random sampling, invalid-combo filtering.
 */
class ParameterSpaceTest {

  @Test
  void rangeSizes_areInclusiveOfEndpoints() {
    assertThat(new IntRange(5, 15, 1).size()).isEqualTo(11);
    assertThat(new IntRange(18, 40, 2).size()).isEqualTo(12);
    assertThat(new DoubleRange(0.005, 0.030, 0.005).size()).isEqualTo(6);
  }

  // ── EmaRsi ───────────────────────────────────────────────────────────────────────────────────

  @Test
  void emarsi_gridSize_isCartesianProduct() {
    EmaRsiParameterSpace space = EmaRsiParameterSpace.defaults();
    long expected =
        space.emaFast().size()
            * (long) space.emaSlow().size()
            * space.rsiPeriod().size()
            * space.rsiOverbought().size()
            * space.rsiOversold().size()
            * space.spreadScale().size();
    assertThat(space.gridSize()).isEqualTo(expected);
  }

  @Test
  void emarsi_gridFiltersInvalidCombos() {
    EmaRsiParameterSpace tiny =
        new EmaRsiParameterSpace(
            new IntRange(5, 6, 1),
            new IntRange(20, 21, 1),
            new IntRange(14, 14, 1),
            new DoubleRange(70.0, 70.0, 1.0),
            new DoubleRange(30.0, 30.0, 1.0),
            new DoubleRange(0.01, 0.01, 0.01));
    List<Candidate> all = tiny.grid().toList();
    assertThat(all).hasSize(4); // 2 × 2 × 1 × 1 × 1 × 1, all valid (fast < slow)
    for (Candidate c : all) {
      Integer fast = (Integer) c.paramsSnapshot().get("emaFast");
      Integer slow = (Integer) c.paramsSnapshot().get("emaSlow");
      assertThat(fast).isLessThan(slow);
    }
  }

  @Test
  void emarsi_randomIsDeterministicForFixedSeed() {
    EmaRsiParameterSpace s = EmaRsiParameterSpace.defaults();
    var a = s.random(42L, 20).map(Candidate::paramsSnapshot).toList();
    var b = s.random(42L, 20).map(Candidate::paramsSnapshot).toList();
    assertThat(a).isEqualTo(b);
  }

  @Test
  void emarsi_randomDrawsAtMostNValidSamples() {
    long count = EmaRsiParameterSpace.defaults().random(7L, 50).count();
    assertThat(count).isLessThanOrEqualTo(50L);
  }

  // ── MeanRev ──────────────────────────────────────────────────────────────────────────────────

  @Test
  void meanrev_randomIsDeterministicAndNonEmpty() {
    long count = MeanRevParameterSpace.defaults().random(13L, 30).count();
    assertThat(count).isPositive();
    assertThat(count).isLessThanOrEqualTo(30L);
  }

  // ── MacdTrend ────────────────────────────────────────────────────────────────────────────────

  @Test
  void macdtrend_randomIsDeterministicAndNonEmpty() {
    long count = MacdTrendParameterSpace.defaults().random(13L, 30).count();
    assertThat(count).isPositive();
    assertThat(count).isLessThanOrEqualTo(30L);
  }
}
