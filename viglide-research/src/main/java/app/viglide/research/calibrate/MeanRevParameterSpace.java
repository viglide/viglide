package app.viglide.research.calibrate;

import app.viglide.core.backtest.FeeModel;
import app.viglide.core.calibrate.Candidate;
import app.viglide.core.calibrate.DoubleRange;
import app.viglide.core.calibrate.IntRange;
import app.viglide.core.calibrate.RandomSearch;
import app.viglide.core.spi.ParameterSpaceProvider;
import app.viglide.examples.meanrev.MeanRevParameters;
import app.viglide.examples.meanrev.MeanReversionRsiBbStrategy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** Search space for {@link MeanRevParameters} (the {@code meanrev} strategy). */
public record MeanRevParameterSpace(
    IntRange rsiPeriod,
    IntRange bbPeriod,
    DoubleRange bbStdevK,
    DoubleRange rsiBuy,
    DoubleRange rsiSell,
    DoubleRange bandTouchTolerance)
    implements ParameterSpaceProvider {

  /** Reasonable default sweep around the textbook RSI(14) / BB(20, 2.0) / 30-70 centre. */
  public static MeanRevParameterSpace defaults() {
    return new MeanRevParameterSpace(
        new IntRange(10, 20, 2),
        new IntRange(14, 30, 2),
        new DoubleRange(1.5, 2.5, 0.25),
        new DoubleRange(20.0, 35.0, 5.0),
        new DoubleRange(65.0, 80.0, 5.0),
        new DoubleRange(0.0, 0.10, 0.02));
  }

  /**
   * {@link java.util.ServiceLoader} requires a public no-arg constructor to discover this class.
   */
  public MeanRevParameterSpace() {
    this(defaults());
  }

  private MeanRevParameterSpace(MeanRevParameterSpace d) {
    this(d.rsiPeriod, d.bbPeriod, d.bbStdevK, d.rsiBuy, d.rsiSell, d.bandTouchTolerance);
  }

  @Override
  public String name() {
    return "meanrev";
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

  public long gridSize() {
    return (long) rsiPeriod.size()
        * bbPeriod.size()
        * bbStdevK.size()
        * rsiBuy.size()
        * rsiSell.size()
        * bandTouchTolerance.size();
  }

  public Stream<Candidate> grid() {
    return IntStream.range(0, rsiPeriod.size())
        .boxed()
        .flatMap(
            a ->
                IntStream.range(0, bbPeriod.size())
                    .boxed()
                    .flatMap(
                        b ->
                            IntStream.range(0, bbStdevK.size())
                                .boxed()
                                .flatMap(
                                    c ->
                                        IntStream.range(0, rsiBuy.size())
                                            .boxed()
                                            .flatMap(
                                                d ->
                                                    IntStream.range(0, rsiSell.size())
                                                        .boxed()
                                                        .flatMap(
                                                            e ->
                                                                IntStream.range(
                                                                        0,
                                                                        bandTouchTolerance.size())
                                                                    .mapToObj(
                                                                        f ->
                                                                            tryBuild(
                                                                                rsiPeriod.at(a),
                                                                                bbPeriod.at(b),
                                                                                bbStdevK.at(c),
                                                                                rsiBuy.at(d),
                                                                                rsiSell.at(e),
                                                                                bandTouchTolerance
                                                                                    .at(f))))))))
        .filter(java.util.Objects::nonNull);
  }

  public Stream<Candidate> random(long seed, int samples) {
    return RandomSearch.draw(seed, samples, this::tryBuild);
  }

  private Candidate tryBuild(Random rng) {
    int rp = rsiPeriod.at(rng.nextInt(rsiPeriod.size()));
    int bp = bbPeriod.at(rng.nextInt(bbPeriod.size()));
    double k = bbStdevK.at(rng.nextInt(bbStdevK.size()));
    double rb = rsiBuy.at(rng.nextInt(rsiBuy.size()));
    double rs = rsiSell.at(rng.nextInt(rsiSell.size()));
    double tol = bandTouchTolerance.at(rng.nextInt(bandTouchTolerance.size()));
    return tryBuild(rp, bp, k, rb, rs, tol);
  }

  private static Candidate tryBuild(
      int rsiPeriod, int bbPeriod, double k, double rsiBuy, double rsiSell, double tol) {
    try {
      MeanRevParameters p = new MeanRevParameters(rsiPeriod, bbPeriod, k, rsiBuy, rsiSell, tol);
      Map<String, Object> snap = new LinkedHashMap<>();
      snap.put("rsiPeriod", p.rsiPeriod());
      snap.put("bbPeriod", p.bbPeriod());
      snap.put("bbStdevK", p.bbStdevK());
      snap.put("rsiBuyThreshold", p.rsiBuyThreshold());
      snap.put("rsiSellThreshold", p.rsiSellThreshold());
      snap.put("bandTouchTolerance", p.bandTouchTolerance());
      return new Candidate(new MeanReversionRsiBbStrategy(p), snap);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
