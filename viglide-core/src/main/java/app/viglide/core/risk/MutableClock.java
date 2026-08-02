package app.viglide.core.risk;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Clock} whose {@link #instant()} can be advanced externally via {@link #set(Instant)}.
 *
 * <p>Used to give {@link RiskManager} a simulated "now" that tracks the current backtest bar
 * instead of wall-clock time — see {@link BacktestClockSync}. This keeps the staleness check's time
 * source explicit and injected rather than reading {@code Instant.now()} inline (CLAUDE.md §5).
 */
public final class MutableClock extends Clock {

  private final AtomicReference<Instant> instant;
  private final ZoneId zone;

  public MutableClock(Instant initial, ZoneId zone) {
    this.instant = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    this.zone = Objects.requireNonNull(zone, "zone");
  }

  /** Advances (or rewinds) the clock to {@code instant}. */
  public void set(Instant instant) {
    this.instant.set(Objects.requireNonNull(instant, "instant"));
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return new MutableClock(instant.get(), zone);
  }

  @Override
  public Instant instant() {
    return instant.get();
  }
}
