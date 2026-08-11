package app.viglide.core.calibrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * PLAN-016 Task C: the reproducibility these tests pin is NFR-7's, and it did not hold before.
 * {@code Stream.generate(() -> tryBuild(sharedRandom)).limit(n)} consumed through {@code
 * .parallel()} drew a different parameter set on every run of the same seed.
 */
class RandomSearchTest {

  /** One "candidate": five sequential draws, exactly the shape every real space uses. */
  private static List<Integer> fiveDraws(Random rng) {
    List<Integer> out = new ArrayList<>(5);
    for (int i = 0; i < 5; i++) {
      out.add(rng.nextInt(1000));
    }
    return out;
  }

  @Test
  void sameSeedProducesTheSameCandidatesInTheSameOrder() {
    var a = RandomSearch.draw(42L, 200, RandomSearchTest::fiveDraws).toList();
    var b = RandomSearch.draw(42L, 200, RandomSearchTest::fiveDraws).toList();

    assertThat(a).isEqualTo(b).hasSize(200);
  }

  @Test
  void differentSeedsProduceDifferentCandidates() {
    var a = RandomSearch.draw(42L, 50, RandomSearchTest::fiveDraws).toList();
    var b = RandomSearch.draw(43L, 50, RandomSearchTest::fiveDraws).toList();

    assertThat(a).isNotEqualTo(b);
  }

  @RepeatedTest(20)
  void parallelConsumptionCannotPerturbTheDraw() {
    // The regression itself. Draw, then consume in parallel exactly as the harnesses do: the
    // result must be identical every repetition. The old Stream.generate form failed this because
    // generation happened *during* parallel consumption; this one cannot, because by the time the
    // stream is returned every draw has already been made on one thread.
    var reference = RandomSearch.draw(42L, 300, RandomSearchTest::fiveDraws).toList();

    var viaParallel =
        RandomSearch.draw(42L, 300, RandomSearchTest::fiveDraws)
            .parallel()
            .map(List::copyOf)
            .collect(Collectors.toList());

    assertThat(viaParallel).isEqualTo(reference);
  }

  @Test
  void rejectedDrawsAreRetriedAndNotCountedTowardTheSampleBudget() {
    // Real spaces return null for combinations their own validation forbids (fundingarb's
    // minFundingEvents >= windowSize). Those must not silently shrink the sample.
    AtomicInteger calls = new AtomicInteger();
    var out =
        RandomSearch.draw(1L, 40, rng -> (calls.incrementAndGet() % 3 == 0) ? rng.nextInt() : null)
            .toList();

    assertThat(out).hasSize(40);
    assertThat(calls.get()).isGreaterThanOrEqualTo(120);
  }

  @Test
  void aSpaceThatRejectsEverythingFailsLoudlyRatherThanHanging() {
    assertThatThrownBy(() -> RandomSearch.draw(1L, 5, rng -> null).toList())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("valid candidates");
  }

  @Test
  void zeroSamplesIsEmptyAndNegativeIsRejected() {
    assertThat(RandomSearch.draw(1L, 0, RandomSearchTest::fiveDraws).toList()).isEmpty();
    assertThatThrownBy(() -> RandomSearch.draw(1L, -1, RandomSearchTest::fiveDraws))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("samples must be >= 0");
  }
}
