package app.viglide.research.k3;

import java.util.ArrayList;
import java.util.List;

/**
 * Bins a signal's stated probabilities and checks whether the observed outcome frequency in each
 * bin matches — the diagnostic behind S2 (ADR-0028): "when the engine says 70%, the outcome must
 * occur about 70% of the time."
 */
public final class ReliabilityCurve {

  private ReliabilityCurve() {}

  /** One bin: how confident the signal claimed to be, versus how often it was actually right. */
  public record Bin(
      double lowerBound,
      double upperBound,
      double meanPredicted,
      double observedFrequency,
      int count) {}

  /**
   * @param predicted stated probabilities, one per observation, each in [0,1]
   * @param outcomes whether the observation's direction was realized, same order/length
   * @param bins number of equal-width bins over [0,1] (bins with zero observations are omitted)
   */
  public static List<Bin> compute(double[] predicted, boolean[] outcomes, int bins) {
    if (predicted.length != outcomes.length) {
      throw new IllegalArgumentException("predicted and outcomes must be the same length");
    }
    if (bins < 1) {
      throw new IllegalArgumentException("bins must be >= 1, got: " + bins);
    }
    double width = 1.0 / bins;
    double[] sumPredicted = new double[bins];
    int[] sumOutcomes = new int[bins];
    int[] count = new int[bins];
    for (int i = 0; i < predicted.length; i++) {
      int bin = binIndexOf(predicted[i], bins, width);
      sumPredicted[bin] += predicted[i];
      if (outcomes[i]) {
        sumOutcomes[bin]++;
      }
      count[bin]++;
    }
    List<Bin> out = new ArrayList<>();
    for (int b = 0; b < bins; b++) {
      if (count[b] == 0) {
        continue;
      }
      out.add(
          new Bin(
              b * width,
              (b + 1) * width,
              sumPredicted[b] / count[b],
              (double) sumOutcomes[b] / count[b],
              count[b]));
    }
    return List.copyOf(out);
  }

  private static int binIndexOf(double p, int bins, double width) {
    int idx = (int) (p / width);
    return Math.min(idx, bins - 1); // p == 1.0 lands in the last bin, not a phantom bins-th one
  }

  /**
   * Expected Calibration Error: the count-weighted mean absolute gap between each bin's stated
   * confidence and its observed frequency — S2's headline number, compared against a pre-registered
   * maximum.
   */
  public static double expectedCalibrationError(List<Bin> curve) {
    int total = curve.stream().mapToInt(Bin::count).sum();
    if (total == 0) {
      return 0.0;
    }
    double weightedError = 0.0;
    for (Bin bin : curve) {
      weightedError += bin.count() * Math.abs(bin.meanPredicted() - bin.observedFrequency());
    }
    return weightedError / total;
  }
}
