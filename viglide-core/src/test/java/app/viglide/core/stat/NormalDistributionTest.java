package app.viglide.core.stat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/** Unit and property tests for {@link NormalDistribution} (PLAN-008 Task C.4). */
class NormalDistributionTest {

  // ── cdf ──────────────────────────────────────────────────────────────────────────────────────

  @Test
  void cdf_atZero_isOneHalf() {
    assertThat(NormalDistribution.cdf(0.0)).isCloseTo(0.5, Offset.offset(1e-9));
  }

  @Test
  void cdf_at196_isApprox975() {
    assertThat(NormalDistribution.cdf(1.96)).isCloseTo(0.9750, Offset.offset(2e-4));
  }

  @Test
  void cdf_atNegative196_isApprox025() {
    assertThat(NormalDistribution.cdf(-1.96)).isCloseTo(0.0250, Offset.offset(2e-4));
  }

  // ── inverseCdf ───────────────────────────────────────────────────────────────────────────────

  @Test
  void inverseCdf_at975_isApprox196() {
    assertThat(NormalDistribution.inverseCdf(0.975)).isCloseTo(1.9600, Offset.offset(1e-3));
  }

  @Test
  void inverseCdf_rejectsBoundaryAndOutOfRangeP() {
    assertThatIllegalArgumentException().isThrownBy(() -> NormalDistribution.inverseCdf(0.0));
    assertThatIllegalArgumentException().isThrownBy(() -> NormalDistribution.inverseCdf(1.0));
    assertThatIllegalArgumentException().isThrownBy(() -> NormalDistribution.inverseCdf(-0.1));
    assertThatIllegalArgumentException().isThrownBy(() -> NormalDistribution.inverseCdf(1.1));
  }

  @Test
  void roundTrip_inverseCdfOfCdf_recoversOriginalX() {
    for (double x : new double[] {-3.0, -1.0, 0.0, 1.0, 3.0}) {
      double recovered = NormalDistribution.inverseCdf(NormalDistribution.cdf(x));
      assertThat(recovered).isCloseTo(x, Offset.offset(1e-6));
    }
  }

  // ── Property tests ───────────────────────────────────────────────────────────────────────────

  @Property
  void cdf_isMonotoneNonDecreasing(
      @ForAll @DoubleRange(min = -6.0, max = 6.0) double a,
      @ForAll @DoubleRange(min = -6.0, max = 6.0) double b) {
    if (a > b) {
      double tmp = a;
      a = b;
      b = tmp;
    }
    assertThat(NormalDistribution.cdf(a)).isLessThanOrEqualTo(NormalDistribution.cdf(b));
  }
}
