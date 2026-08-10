package app.viglide.research.cli.fixtures;

import app.viglide.core.domain.PortfolioContext;
import app.viglide.core.domain.PositionShape;
import app.viglide.core.domain.TargetPosition;
import app.viglide.core.spi.PortfolioStrategy;
import app.viglide.core.spi.StrategyMetadata;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-only {@link PortfolioStrategy}: targets full-weight {@link
 * PositionShape#DELTA_NEUTRAL_CARRY} for every symbol present in context. Used only to exercise
 * {@code PortfolioCalibrateCli} end to end (PLAN-022 Task B).
 */
public final class FixtureCarryPortfolioStrategy implements PortfolioStrategy {

  @Override
  public List<TargetPosition> evaluate(PortfolioContext context) {
    List<TargetPosition> out = new ArrayList<>();
    for (String symbol : context.bySymbol().keySet()) {
      out.add(
          new TargetPosition(
              symbol, BigDecimal.ONE, PositionShape.DELTA_NEUTRAL_CARRY, List.of(), "fixture"));
    }
    return out;
  }

  @Override
  public StrategyMetadata metadata() {
    return new StrategyMetadata("fixture-carry-cli", "1.0", "test-only");
  }
}
