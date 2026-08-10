package app.viglide.core.spi.fixtures;

import app.viglide.core.domain.PortfolioContext;
import app.viglide.core.domain.TargetPosition;
import app.viglide.core.spi.PortfolioStrategy;
import app.viglide.core.spi.StrategyMetadata;
import java.util.List;

/** Test-only {@link PortfolioStrategy}, never targets anything — used only to exercise the SPI. */
public final class FixturePortfolioStrategy implements PortfolioStrategy {

  @Override
  public List<TargetPosition> evaluate(PortfolioContext context) {
    return List.of();
  }

  @Override
  public StrategyMetadata metadata() {
    return new StrategyMetadata("fixture-portfolio", "1.0", "test-only");
  }
}
