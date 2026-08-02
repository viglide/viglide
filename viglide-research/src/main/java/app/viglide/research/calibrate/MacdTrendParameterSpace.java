package app.viglide.research.calibrate;

import app.viglide.core.backtest.FeeModel;
import app.viglide.core.calibrate.Candidate;
import app.viglide.core.calibrate.DoubleRange;
import app.viglide.core.calibrate.IntRange;
import app.viglide.core.spi.ParameterSpaceProvider;
import app.viglide.examples.macdtrend.MacdTrendParameters;
import app.viglide.examples.macdtrend.TrendFollowMacdAtrStrategy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Search space for {@link MacdTrendParameters} (the {@code macdtrend} strategy). 7-dimensional —
 * the grid generator uses nested loops rather than a {@code flatMap} chain for legibility.
 */
public record MacdTrendParameterSpace(
    IntRange macdFast,
    IntRange macdSlow,
    IntRange macdSignal,
    IntRange atrPeriod,
    IntRange atrLookback,
    DoubleRange atrActiveRatio,
    DoubleRange histogramScale)
    implements ParameterSpaceProvider {

  /** Reasonable default sweep around the textbook MACD(12/26/9), ATR(14) centre. */
  public static MacdTrendParameterSpace defaults() {
    return new MacdTrendParameterSpace(
        new IntRange(8, 14, 2),
        new IntRange(21, 30, 3),
        new IntRange(7, 11, 2),
        new IntRange(10, 20, 2),
        new IntRange(40, 100, 20),
        new DoubleRange(0.8, 1.4, 0.2),
        new DoubleRange(0.1, 0.7, 0.2));
  }

  /**
   * {@link java.util.ServiceLoader} requires a public no-arg constructor to discover this class.
   */
  public MacdTrendParameterSpace() {
    this(defaults());
  }

  private MacdTrendParameterSpace(MacdTrendParameterSpace d) {
    this(
        d.macdFast,
        d.macdSlow,
        d.macdSignal,
        d.atrPeriod,
        d.atrLookback,
        d.atrActiveRatio,
        d.histogramScale);
  }

  @Override
  public String name() {
    return "macdtrend";
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
    return (long) macdFast.size()
        * macdSlow.size()
        * macdSignal.size()
        * atrPeriod.size()
        * atrLookback.size()
        * atrActiveRatio.size()
        * histogramScale.size();
  }

  public Stream<Candidate> grid() {
    List<Candidate> out = new ArrayList<>();
    for (int a = 0; a < macdFast.size(); a++) {
      for (int b = 0; b < macdSlow.size(); b++) {
        for (int c = 0; c < macdSignal.size(); c++) {
          for (int d = 0; d < atrPeriod.size(); d++) {
            for (int e = 0; e < atrLookback.size(); e++) {
              for (int f = 0; f < atrActiveRatio.size(); f++) {
                for (int g = 0; g < histogramScale.size(); g++) {
                  Candidate cand =
                      tryBuild(
                          macdFast.at(a),
                          macdSlow.at(b),
                          macdSignal.at(c),
                          atrPeriod.at(d),
                          atrLookback.at(e),
                          atrActiveRatio.at(f),
                          histogramScale.at(g));
                  if (cand != null) out.add(cand);
                }
              }
            }
          }
        }
      }
    }
    return out.stream();
  }

  public Stream<Candidate> random(long seed, int samples) {
    Random rng = new Random(seed);
    return Stream.generate(() -> tryBuild(rng)).filter(java.util.Objects::nonNull).limit(samples);
  }

  private Candidate tryBuild(Random rng) {
    return tryBuild(
        macdFast.at(rng.nextInt(macdFast.size())),
        macdSlow.at(rng.nextInt(macdSlow.size())),
        macdSignal.at(rng.nextInt(macdSignal.size())),
        atrPeriod.at(rng.nextInt(atrPeriod.size())),
        atrLookback.at(rng.nextInt(atrLookback.size())),
        atrActiveRatio.at(rng.nextInt(atrActiveRatio.size())),
        histogramScale.at(rng.nextInt(histogramScale.size())));
  }

  private static Candidate tryBuild(
      int macdFast,
      int macdSlow,
      int macdSignal,
      int atrPeriod,
      int atrLookback,
      double atrActiveRatio,
      double histogramScale) {
    try {
      MacdTrendParameters p =
          new MacdTrendParameters(
              macdFast,
              macdSlow,
              macdSignal,
              atrPeriod,
              atrLookback,
              atrActiveRatio,
              histogramScale);
      Map<String, Object> snap = new LinkedHashMap<>();
      snap.put("macdFast", p.macdFast());
      snap.put("macdSlow", p.macdSlow());
      snap.put("macdSignal", p.macdSignal());
      snap.put("atrPeriod", p.atrPeriod());
      snap.put("atrLookback", p.atrLookback());
      snap.put("atrActiveRatio", p.atrActiveRatio());
      snap.put("histogramScale", p.histogramScale());
      return new Candidate(new TrendFollowMacdAtrStrategy(p), snap);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
