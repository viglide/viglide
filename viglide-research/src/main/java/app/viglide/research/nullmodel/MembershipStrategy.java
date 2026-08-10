package app.viglide.research.nullmodel;

import app.viglide.core.domain.Direction;
import app.viglide.core.domain.Factor;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.spi.StrategyKind;
import app.viglide.core.spi.StrategyMetadata;
import app.viglide.core.spi.StrategyStatus;
import app.viglide.core.spi.TradingStrategy;
import java.time.Instant;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a precomputed membership series into a single-symbol {@link TradingStrategy}, so a
 * cross-sectional book can be executed through the already-trusted two-leg engine ({@code
 * PortfolioFundingArbHarnessV2}) rather than a second, divergent execution path built just for null
 * models. Lifted out of {@code viglide-strategies/src/test} by PLAN-023 Task B.
 *
 * <p>BUY while this symbol is a member as of the most recent membership instant at or before {@code
 * asOf}; SELL otherwise. Confidence is a flat {@code 1.0} — membership is a binary decision already
 * made upstream, and reusing it as a confidence score would put it through the Risk Manager's
 * confidence floor a second time, for the same reason {@code PortfolioBacktestHarness} synthesises
 * {@code 1.0} rather than reusing {@code targetWeight}.
 *
 * <p>Marked {@link StrategyStatus#BENCHMARK_ONLY}: this is a measurement instrument, and it must
 * never be eligible for an order-placing mode. The runtime enforces that status, so the guarantee
 * is structural rather than a comment.
 */
public final class MembershipStrategy implements TradingStrategy {

  private final String symbol;
  private final NavigableMap<Instant, Boolean> membership;
  private final String label;

  public MembershipStrategy(
      String symbol, NavigableMap<Instant, Boolean> membership, String label) {
    this.symbol = Objects.requireNonNull(symbol, "symbol");
    this.membership = Objects.requireNonNull(membership, "membership");
    this.label = Objects.requireNonNull(label, "label");
  }

  @Override
  public Optional<TechnicalSignal> evaluate(MarketContext context) {
    Instant asOf = context.asOf();
    var floor = membership.floorEntry(asOf);
    boolean member = floor != null && floor.getValue();
    String detail = "member=" + member + " asOf=" + asOf;
    return Optional.of(
        new TechnicalSignal(
            context.symbol(),
            member ? Direction.BUY : Direction.SELL,
            1.0,
            List.of(new Factor("MEMBERSHIP", detail, 1.0)),
            (member ? "BUY " : "SELL ") + symbol + ": " + detail,
            asOf));
  }

  @Override
  public StrategyMetadata metadata() {
    return new StrategyMetadata(
        label + "[" + symbol + "]",
        "1.0",
        "Precomputed membership series executed as a single-symbol strategy (null-model baseline)",
        StrategyKind.FUNDING_AWARE,
        StrategyStatus.BENCHMARK_ONLY);
  }
}
