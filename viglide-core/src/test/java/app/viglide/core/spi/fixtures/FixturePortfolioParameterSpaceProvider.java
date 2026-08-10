package app.viglide.core.spi.fixtures;

import app.viglide.core.backtest.FeeModel;
import app.viglide.core.calibrate.PortfolioCandidate;
import app.viglide.core.spi.PortfolioParameterSpaceProvider;
import java.util.Map;
import java.util.stream.Stream;

/** Test-only {@link PortfolioParameterSpaceProvider}, registered via {@code META-INF/services}. */
public final class FixturePortfolioParameterSpaceProvider
    implements PortfolioParameterSpaceProvider {

  @Override
  public String name() {
    return "fixture-portfolio";
  }

  @Override
  public Stream<PortfolioCandidate> grid(FeeModel feeModel) {
    return Stream.of(new PortfolioCandidate(new FixturePortfolioStrategy(), Map.of("dummy", 1)));
  }

  @Override
  public Stream<PortfolioCandidate> random(long seed, int samples, FeeModel feeModel) {
    return grid(feeModel).limit(samples);
  }
}
