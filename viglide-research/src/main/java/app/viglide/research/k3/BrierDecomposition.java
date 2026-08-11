package app.viglide.research.k3;

import java.util.List;

/**
 * Murphy (1973) three-term decomposition of the Brier score, computed over the same bins as {@link
 * ReliabilityCurve} — S2's other half (ADR-0028): reliability is the calibration-error term this
 * gate is actually about; resolution and uncertainty are reported alongside for context (a signal
 * can be perfectly calibrated and still useless if its resolution is near zero — everything binned
 * at the base rate).
 */
public final class BrierDecomposition {

  private BrierDecomposition() {}

  /**
   * {@code brierScore} is the raw mean-squared error of the un-binned predictions; {@code
   * reliability + uncertainty - resolution} approximates it (the standard decomposition identity),
   * with a small gap from binning {@code reliability}/{@code resolution} while {@code brierScore}
   * itself is computed on the un-binned predictions.
   */
  public record Result(
      double brierScore, double reliability, double resolution, double uncertainty) {}

  public static Result compute(double[] predicted, boolean[] outcomes, int bins) {
    if (predicted.length != outcomes.length) {
      throw new IllegalArgumentException("predicted and outcomes must be the same length");
    }
    int n = predicted.length;
    if (n == 0) {
      return new Result(0.0, 0.0, 0.0, 0.0);
    }

    double brier = 0.0;
    double baseRate = 0.0;
    for (int i = 0; i < n; i++) {
      double o = outcomes[i] ? 1.0 : 0.0;
      brier += (predicted[i] - o) * (predicted[i] - o);
      baseRate += o;
    }
    brier /= n;
    baseRate /= n;

    List<ReliabilityCurve.Bin> curve = ReliabilityCurve.compute(predicted, outcomes, bins);
    double reliability = 0.0;
    double resolution = 0.0;
    for (ReliabilityCurve.Bin bin : curve) {
      double calibrationGap = bin.meanPredicted() - bin.observedFrequency();
      reliability += bin.count() * calibrationGap * calibrationGap;
      double resolutionGap = bin.observedFrequency() - baseRate;
      resolution += bin.count() * resolutionGap * resolutionGap;
    }
    reliability /= n;
    resolution /= n;
    double uncertainty = baseRate * (1.0 - baseRate);

    return new Result(brier, reliability, resolution, uncertainty);
  }
}
