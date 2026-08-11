package app.viglide.research.calibrate;

import app.viglide.core.backtest.FeeModel;
import app.viglide.core.calibrate.Candidate;
import app.viglide.core.calibrate.DoubleRange;
import app.viglide.core.calibrate.IntRange;
import app.viglide.core.calibrate.RandomSearch;
import app.viglide.core.spi.ParameterSpaceProvider;
import app.viglide.examples.emarsi.EmaCrossoverRsiStrategy;
import app.viglide.examples.emarsi.StrategyParameters;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Search space for {@link StrategyParameters} (the {@code emarsi} strategy). Each dimension is an
 * independent {@link IntRange} or {@link DoubleRange}; the Cartesian product is the full grid.
 * Random sampling draws independently from each dimension using a seeded {@link Random} so two runs
 * with the same seed produce identical sequences (NFR-7).
 */
public record EmaRsiParameterSpace(
    IntRange emaFast,
    IntRange emaSlow,
    IntRange rsiPeriod,
    DoubleRange rsiOverbought,
    DoubleRange rsiOversold,
    DoubleRange spreadScale)
    implements ParameterSpaceProvider {

  /** Reasonable default sweep around the textbook 9/21/14/70/30/0.01 centre. */
  public static EmaRsiParameterSpace defaults() {
    return new EmaRsiParameterSpace(
        new IntRange(5, 15, 1),
        new IntRange(18, 40, 2),
        new IntRange(10, 20, 2),
        new DoubleRange(65.0, 80.0, 5.0),
        new DoubleRange(20.0, 35.0, 5.0),
        new DoubleRange(0.005, 0.030, 0.005));
  }

  /**
   * {@link java.util.ServiceLoader} requires a public no-arg constructor to discover this class.
   */
  public EmaRsiParameterSpace() {
    this(defaults());
  }

  private EmaRsiParameterSpace(EmaRsiParameterSpace d) {
    this(d.emaFast, d.emaSlow, d.rsiPeriod, d.rsiOverbought, d.rsiOversold, d.spreadScale);
  }

  @Override
  public String name() {
    return "emarsi";
  }

  /** {@link ParameterSpaceProvider} entry point — {@code feeModel} is unused by this strategy. */
  @Override
  public Stream<Candidate> grid(FeeModel feeModel) {
    return grid();
  }

  /** {@link ParameterSpaceProvider} entry point — {@code feeModel} is unused by this strategy. */
  @Override
  public Stream<Candidate> random(long seed, int samples, FeeModel feeModel) {
    return random(seed, samples);
  }

  /** Total Cartesian product size — most combinations are invalid (filtered downstream). */
  public long gridSize() {
    return (long) emaFast.size()
        * emaSlow.size()
        * rsiPeriod.size()
        * rsiOverbought.size()
        * rsiOversold.size()
        * spreadScale.size();
  }

  /** Streams every valid grid combination as a ready-to-evaluate {@link Candidate}. */
  public Stream<Candidate> grid() {
    return IntStream.range(0, emaFast.size())
        .boxed()
        .flatMap(
            i ->
                IntStream.range(0, emaSlow.size())
                    .boxed()
                    .flatMap(
                        j ->
                            IntStream.range(0, rsiPeriod.size())
                                .boxed()
                                .flatMap(
                                    k ->
                                        IntStream.range(0, rsiOverbought.size())
                                            .boxed()
                                            .flatMap(
                                                a ->
                                                    IntStream.range(0, rsiOversold.size())
                                                        .boxed()
                                                        .flatMap(
                                                            b ->
                                                                IntStream.range(
                                                                        0, spreadScale.size())
                                                                    .mapToObj(
                                                                        c ->
                                                                            tryBuild(
                                                                                emaFast.at(i),
                                                                                emaSlow.at(j),
                                                                                rsiPeriod.at(k),
                                                                                rsiOverbought.at(a),
                                                                                rsiOversold.at(b),
                                                                                spreadScale.at(
                                                                                    c))))))))
        .filter(java.util.Objects::nonNull);
  }

  /** Streams up to {@code samples} valid candidates drawn from {@code seed}. */
  public Stream<Candidate> random(long seed, int samples) {
    return RandomSearch.draw(seed, samples, this::tryBuild);
  }

  private Candidate tryBuild(Random rng) {
    int ef = emaFast.at(rng.nextInt(emaFast.size()));
    int es = emaSlow.at(rng.nextInt(emaSlow.size()));
    int rp = rsiPeriod.at(rng.nextInt(rsiPeriod.size()));
    double rob = rsiOverbought.at(rng.nextInt(rsiOverbought.size()));
    double ros = rsiOversold.at(rng.nextInt(rsiOversold.size()));
    double ss = spreadScale.at(rng.nextInt(spreadScale.size()));
    return tryBuild(ef, es, rp, rob, ros, ss);
  }

  private static Candidate tryBuild(
      int emaFast, int emaSlow, int rsiPeriod, double rob, double ros, double spread) {
    try {
      StrategyParameters p = new StrategyParameters(emaFast, emaSlow, rsiPeriod, rob, ros, spread);
      Map<String, Object> snap = new LinkedHashMap<>();
      snap.put("emaFast", p.emaFast());
      snap.put("emaSlow", p.emaSlow());
      snap.put("rsiPeriod", p.rsiPeriod());
      snap.put("rsiOverbought", p.rsiOverbought());
      snap.put("rsiOversold", p.rsiOversold());
      snap.put("spreadScale", p.spreadScale());
      return new Candidate(new EmaCrossoverRsiStrategy(p), snap);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
