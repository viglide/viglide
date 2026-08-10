package app.viglide.research.cli.fixtures;

import app.viglide.core.backtest.FeeModel;
import app.viglide.core.calibrate.PortfolioCandidate;
import app.viglide.core.spi.PortfolioParameterSpaceProvider;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Test-only {@link PortfolioParameterSpaceProvider}, registered via {@code META-INF/services} —
 * exercises {@code PortfolioCalibrateCli} (PLAN-022 Task B) without a real strategy on the
 * classpath. Two candidates, differing only by label, so a CLI test can assert on {@code
 * candidatesRequested}/{@code trials} without depending on a real search-surface size.
 */
public final class FixtureCarryPortfolioParameterSpaceProvider
    implements PortfolioParameterSpaceProvider {

  @Override
  public String name() {
    return "fixture-carry-cli";
  }

  @Override
  public Stream<PortfolioCandidate> grid(FeeModel feeModel) {
    return Stream.of(
        new PortfolioCandidate(new FixtureCarryPortfolioStrategy(), Map.of("variant", "a")),
        new PortfolioCandidate(new FixtureCarryPortfolioStrategy(), Map.of("variant", "b")));
  }

  @Override
  public Stream<PortfolioCandidate> random(long seed, int samples, FeeModel feeModel) {
    return grid(feeModel).limit(samples);
  }
}
