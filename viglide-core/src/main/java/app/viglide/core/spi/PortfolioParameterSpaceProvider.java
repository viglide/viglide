package app.viglide.core.spi;

import app.viglide.core.backtest.FeeModel;
import app.viglide.core.calibrate.PortfolioCandidate;
import java.util.stream.Stream;

/**
 * {@link ParameterSpaceProvider}'s cross-sectional counterpart (PLAN-022 Task B): a {@link
 * PortfolioStrategy}'s calibration grid/random search space, discovered the same way and for the
 * same reason — a public calibration CLI ({@code viglide-research}) must be able to sweep a private
 * cross-sectional strategy's parameter space (e.g. {@code carryranking}) without ever importing
 * that strategy's package.
 *
 * <p>{@code feeModel} is threaded through explicitly for the identical reason {@link
 * ParameterSpaceProvider#grid} takes it: it must match the calibration run's own {@link
 * app.viglide.core.backtest.BacktestConfig#fees()}, not whatever a space's own {@code defaults()}
 * might otherwise assume.
 */
public interface PortfolioParameterSpaceProvider {

  /** Strategy name this parameter space belongs to — matches {@link StrategyProvider#name()}. */
  String name();

  /** Streams every valid grid combination as a ready-to-evaluate {@link PortfolioCandidate}. */
  Stream<PortfolioCandidate> grid(FeeModel feeModel);

  /** Streams up to {@code samples} valid candidates drawn from {@code seed}. */
  Stream<PortfolioCandidate> random(long seed, int samples, FeeModel feeModel);
}
