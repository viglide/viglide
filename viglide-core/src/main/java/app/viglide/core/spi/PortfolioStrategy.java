package app.viglide.core.spi;

import app.viglide.core.domain.Direction;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.PortfolioContext;
import app.viglide.core.domain.PositionShape;
import app.viglide.core.domain.TargetPosition;
import app.viglide.core.domain.TechnicalSignal;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The cross-sectional counterpart of {@link TradingStrategy} (PLAN-015 Task A, ADR-0018): instead
 * of a single-symbol {@code direction}/{@code confidence} verdict, a {@code PortfolioStrategy}
 * declares a <em>target book</em> across every symbol it has an opinion on in one call. This is
 * what makes cross-sectional signals (rank all N pairs, hold the top-k) and paired/basis trades
 * expressible at all — {@link TradingStrategy}'s single-symbol, single-timestamp shape structurally
 * cannot represent them (review finding F8).
 *
 * <p>{@link TradingStrategy} is <strong>not removed or deprecated</strong> by this interface's
 * introduction — see {@link #ofSingle} for the compatibility adapter, and ADR-0018 for the full
 * "why targets, not actions" reasoning and the compatibility guarantee.
 *
 * <p>Implementations must be deterministic and side-effect free, exactly like {@link
 * TradingStrategy} (NFR-7): identical {@link PortfolioContext} in, identical {@code
 * List<TargetPosition>} out, including ordering.
 */
public interface PortfolioStrategy {

  /**
   * Evaluates the full cross-sectional context and returns this strategy's desired book. A symbol
   * absent from the result is implicitly targeted at weight {@code 0} (see {@link TargetPosition}'s
   * Javadoc) — the returned list only needs to name symbols the strategy has a non-default opinion
   * about.
   */
  List<TargetPosition> evaluate(PortfolioContext context);

  /** Returns the metadata that identifies this strategy for audit and display purposes. */
  StrategyMetadata metadata();

  /**
   * Adapts an existing single-symbol {@link TradingStrategy} to the {@link PortfolioStrategy}
   * shape, so every strategy written against the older interface keeps working unchanged and
   * harnesses can standardise on one interface internally (ADR-0018).
   *
   * <p><strong>Direction-to-weight mapping</strong> depends on {@link StrategyMetadata#kind()} of
   * the wrapped strategy, because {@code BUY}/{@code SELL} mean different things for the two {@link
   * StrategyKind} values (CLAUDE.md §11):
   *
   * <ul>
   *   <li>{@link StrategyKind#OHLCV}: {@code BUY} → target weight {@code +confidence}, shape {@link
   *       PositionShape#SPOT_ONLY}; {@code SELL} → target weight {@code -confidence}, same shape —
   *       the sign of the weight carries the direction, exactly mirroring the old single-leg
   *       execution semantics for a plain directional strategy.
   *   <li>{@link StrategyKind#FUNDING_AWARE}: {@code BUY} → target weight {@code +confidence},
   *       shape {@link PositionShape#DELTA_NEUTRAL_CARRY}; {@code SELL} → <strong>omitted (target
   *       flat)</strong>, never a negative weight. A negative weight on a {@code
   *       DELTA_NEUTRAL_CARRY} target would read as "reverse the pairing into a naked short" — the
   *       exact misinterpretation the single-leg order path is forbidden from making (CLAUDE.md
   *       §11, ADR-0014). {@code SELL}'s real meaning, "exit the basis trade," is flat, not short.
   *   <li>{@code HOLD}, or {@link TradingStrategy#evaluate} returning empty, → omitted from the
   *       result for both kinds.
   * </ul>
   *
   * <p><strong>Known limitation, disclosed rather than hidden (ADR-0018):</strong> {@code HOLD}
   * mapping to "omitted / target flat" is a real behaviour difference from the harnesses {@link
   * TradingStrategy} implementations run through today ({@code BacktestHarness}, the v2 funding-arb
   * harness, {@code PortfolioBacktestHarness}, the live loop) — those treat {@code HOLD} as "no
   * action, leave whatever is currently open exactly as it is," because a target-list model has no
   * third "no opinion" state distinct from "flat" (see {@link TargetPosition}'s Javadoc). This is
   * harmless for {@code OHLCV} strategies, which have no deliberate hold-open-through-{@code HOLD}
   * logic. <strong>It is not safe for any {@code FUNDING_AWARE} strategy that relies on exit
   * hysteresis</strong> (i.e. treats consecutive {@code HOLD} evaluations as "stay open, exit not
   * yet confirmed") — this adapter would silently close such a position every time it evaluates
   * {@code HOLD}, defeating the hysteresis entirely. A {@code FUNDING_AWARE} strategy with that
   * shape of state should keep running through its dedicated two-leg harnesses, or be re-expressed
   * directly against {@link PortfolioStrategy} (as PLAN-015 Task E's carry-ranking strategy is),
   * never wrapped with this adapter.
   */
  static PortfolioStrategy ofSingle(TradingStrategy strategy, String symbol) {
    Objects.requireNonNull(strategy, "strategy");
    Objects.requireNonNull(symbol, "symbol");
    if (symbol.isBlank()) {
      throw new IllegalArgumentException("symbol must not be blank");
    }
    return new SingleStrategyAdapter(strategy, symbol);
  }

  /**
   * Package-private so only {@link #ofSingle} constructs it; keeps the adapter's contract in one
   * place.
   */
  final class SingleStrategyAdapter implements PortfolioStrategy {
    private final TradingStrategy delegate;
    private final String symbol;

    private SingleStrategyAdapter(TradingStrategy delegate, String symbol) {
      this.delegate = delegate;
      this.symbol = symbol;
    }

    @Override
    public List<TargetPosition> evaluate(PortfolioContext context) {
      Objects.requireNonNull(context, "context");
      Optional<MarketContext> ctx = context.forSymbol(symbol);
      if (ctx.isEmpty()) {
        return List.of();
      }
      Optional<TechnicalSignal> maybeSignal = delegate.evaluate(ctx.get());
      if (maybeSignal.isEmpty()) {
        return List.of();
      }
      TechnicalSignal signal = maybeSignal.get();
      if (signal.direction() == Direction.HOLD) {
        return List.of();
      }

      StrategyKind kind = delegate.metadata().kind();
      BigDecimal confidence = BigDecimal.valueOf(signal.confidence());
      TargetPosition target =
          switch (kind) {
            case OHLCV ->
                new TargetPosition(
                    symbol,
                    signal.direction() == Direction.BUY ? confidence : confidence.negate(),
                    PositionShape.SPOT_ONLY,
                    signal.factors(),
                    signal.explanation());
            case FUNDING_AWARE -> {
              if (signal.direction() == Direction.SELL) {
                // "Exit the basis trade" is flat, never a negative (naked-short) weight.
                yield null;
              }
              yield new TargetPosition(
                  symbol,
                  confidence,
                  PositionShape.DELTA_NEUTRAL_CARRY,
                  signal.factors(),
                  signal.explanation());
            }
          };
      return target == null ? List.of() : List.of(target);
    }

    @Override
    public StrategyMetadata metadata() {
      return delegate.metadata();
    }
  }
}
