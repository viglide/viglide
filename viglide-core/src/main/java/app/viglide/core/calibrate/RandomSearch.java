package app.viglide.core.calibrate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Draws a reproducible random sample from a parameter space.
 *
 * <p><strong>Why this exists rather than the obvious one-liner.</strong> Every parameter space in
 * this project independently wrote the same thing:
 *
 * <pre>
 *   Random rng = new Random(seed);
 *   return Stream.generate(() -&gt; tryBuild(rng)).filter(Objects::nonNull).limit(samples);
 * </pre>
 *
 * <p>which looks deterministic and is not, because the calibration harnesses consume it through
 * {@code .parallel()}. {@link Stream#generate} is documented <em>unordered</em>; under parallel
 * consumption several workers call the supplier concurrently, and a single candidate's handful of
 * sequential {@code rng.nextInt()} calls interleave across threads. The <em>set</em> of draws is
 * well defined — {@link Random} is thread-safe — but which draws combine into which candidate is
 * not, so the search explores a different region on every run of the same seed. Two runs of {@code
 * seed=42} over byte-identical data returned 27 and 28 surviving candidates (PLAN-016 Task C,
 * 2026-08-11).
 *
 * <p>For a study whose reproducibility rests on a pre-registered seed, that is not a performance
 * detail — it is the reproducibility claim. Drawing eagerly on the calling thread costs nothing
 * (candidates are small records and {@code samples} is bounded) and makes the seed mean what every
 * plan, note and manifest in this repository says it means.
 *
 * <p>The harnesses also materialise before going parallel, deliberately. Two places, because a
 * provider that is only deterministic when its consumer happens to collect first is a trap for the
 * next provider, and a harness that is only deterministic when its providers happen to be eager is
 * a trap for the next harness. Neither alone would have prevented this.
 */
public final class RandomSearch {

  private RandomSearch() {}

  /**
   * How many times to call {@code attempt} per requested sample before concluding the space cannot
   * produce them. A parameter space with heavily constrained combinations (fundingarb's {@code
   * minFundingEvents >= windowSize}, for instance) legitimately rejects many draws, so the bound is
   * generous; it exists only so an impossible space fails with a diagnosis instead of hanging.
   */
  private static final int MAX_ATTEMPTS_PER_SAMPLE = 1000;

  /**
   * Draws {@code samples} candidates sequentially from one seeded {@link Random}.
   *
   * @param seed the pre-registered seed; identical seeds yield identical, identically-ordered
   *     results for a given space
   * @param samples how many valid candidates to return
   * @param attempt builds one candidate from the generator, returning {@code null} for a draw that
   *     violates the space's own constraints — those are retried, not counted
   * @return an ordered, already-materialised stream; safe to consume in parallel
   */
  public static <T> Stream<T> draw(long seed, int samples, Function<Random, T> attempt) {
    Objects.requireNonNull(attempt, "attempt");
    if (samples < 0) {
      throw new IllegalArgumentException("samples must be >= 0, got: " + samples);
    }
    Random rng = new Random(seed);
    List<T> out = new ArrayList<>(samples);
    long budget = (long) samples * MAX_ATTEMPTS_PER_SAMPLE;
    long attempts = 0;
    while (out.size() < samples) {
      if (attempts++ >= budget) {
        throw new IllegalStateException(
            "random search drew "
                + attempts
                + " times and produced only "
                + out.size()
                + " of "
                + samples
                + " valid candidates — the parameter space's constraints reject almost every"
                + " combination, which is a bug in the space, not a reason to return fewer");
      }
      T candidate = attempt.apply(rng);
      if (candidate != null) {
        out.add(candidate);
      }
    }
    return out.stream();
  }
}
