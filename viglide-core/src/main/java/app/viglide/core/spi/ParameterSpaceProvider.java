package app.viglide.core.spi;

import app.viglide.core.backtest.FeeModel;
import app.viglide.core.calibrate.Candidate;
import java.util.stream.Stream;

/**
 * Calibration counterpart to {@link StrategyProvider} (PLAN-018 R-2.2): a strategy's calibration
 * grid/random search space, discovered the same way and for the same reason — {@code CalibrateCli}
 * (public, {@code viglide-research}) must be able to sweep a private strategy's parameter space
 * (e.g. {@code fundingarb}) without ever importing that strategy's package. Not every {@link
 * StrategyProvider} needs one of these (a strategy with no calibratable parameters simply registers
 * no {@link ParameterSpaceProvider}).
 *
 * <p>{@code feeModel} is threaded through explicitly rather than baked into each space's own {@code
 * defaults()}, because it must match the calibration run's own {@link
 * app.viglide.core.backtest.BacktestConfig#fees()} (PLAN-013 Task E, review finding F6) —
 * implementations that don't need it simply ignore the argument.
 */
public interface ParameterSpaceProvider {

  /** Strategy name this parameter space belongs to — matches {@link StrategyProvider#name()}. */
  String name();

  /** Streams every valid grid combination as a ready-to-evaluate {@link Candidate}. */
  Stream<Candidate> grid(FeeModel feeModel);

  /** Streams up to {@code samples} valid candidates drawn from {@code seed}. */
  Stream<Candidate> random(long seed, int samples, FeeModel feeModel);
}
