package app.viglide.core.spi.fixtures;

import app.viglide.core.backtest.FeeModel;
import app.viglide.core.calibrate.Candidate;
import app.viglide.core.spi.ParameterSpaceProvider;
import java.util.Map;
import java.util.stream.Stream;

/** Test-only {@link ParameterSpaceProvider}, registered via {@code META-INF/services}. */
public final class FixtureParameterSpaceProvider implements ParameterSpaceProvider {

  @Override
  public String name() {
    return "fixture";
  }

  @Override
  public Stream<Candidate> grid(FeeModel feeModel) {
    return Stream.of(
        new Candidate(new FixtureStrategyProvider().create(Map.of()), Map.of("dummy", 1)));
  }

  @Override
  public Stream<Candidate> random(long seed, int samples, FeeModel feeModel) {
    return grid(feeModel).limit(samples);
  }
}
