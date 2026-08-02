package app.viglide.core.risk;

import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import java.util.Objects;

/**
 * Decorates a {@link RiskManagerPort} so the wrapped Risk Manager's {@link MutableClock} is
 * advanced to {@link MarketContext#asOf()} before every gate call (F7, PLAN-007 Task C).
 *
 * <p>Backtests replay historical bars whose timestamps are always far from wall-clock "now". A
 * {@link RiskManager} constructed once with {@code Clock.systemUTC()} would therefore see every
 * historical signal as stale and refuse it. This decorator keeps the Risk Manager's notion of "now"
 * pinned to the bar currently being evaluated, so {@code maxStaleInputAge} only trips when a
 * strategy genuinely emits an old signal — never spuriously during replay.
 *
 * <p>Live callers do not need this: they gate through {@link RiskManager} directly, constructed
 * with {@code Clock.systemUTC()}, whose {@code instant()} already tracks real time on every call.
 */
public final class BacktestClockSync implements RiskManagerPort {

  private final RiskManagerPort delegate;
  private final MutableClock clock;

  public BacktestClockSync(RiskManagerPort delegate, MutableClock clock) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public ExecutionDecision gate(TechnicalSignal signal, PortfolioState state, MarketContext ctx) {
    clock.set(ctx.asOf());
    return delegate.gate(signal, state, ctx);
  }

  @Override
  public RiskParameters riskParameters() {
    return delegate.riskParameters();
  }
}
