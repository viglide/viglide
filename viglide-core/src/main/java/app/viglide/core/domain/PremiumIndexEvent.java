package app.viglide.core.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One sampled observation of a Binance perpetual-future's premium index — {@code (markPrice -
 * indexPrice) / indexPrice}, expressed as a decimal — at a point in time <em>before</em> the
 * funding interval it belongs to has settled.
 *
 * <p>This is a genuinely different signal from {@link FundingEvent}: a funding event is only
 * knowable at the instant it settles (every 8h), while the premium index is observable continuously
 * and is exactly what Binance itself averages (with a small interest-rate clamp) to
 * <em>produce</em> the next settlement (PLAN-015 Task C). A strategy that reads this instead of, or
 * alongside, realised funding history is nowcasting the next settlement rather than extrapolating
 * from past ones.
 *
 * <p>Unlike a funding rate, the premium index can be negative — no validation on sign.
 */
public record PremiumIndexEvent(Instant time, BigDecimal value) {

  /** Validates non-null fields. */
  public PremiumIndexEvent {
    Objects.requireNonNull(time, "time");
    Objects.requireNonNull(value, "value");
  }
}
