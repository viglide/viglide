package app.viglide.core.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One published perpetual-future funding payment.
 *
 * <p>Binance publishes a funding rate every 8 hours; positive rates mean longs pay shorts and vice
 * versa. Rate is expressed as a decimal (e.g. {@code 0.0001} = 0.01% per 8h ≈ 11% annualised
 * carry).
 *
 * <p>Unlike candle prices, the funding rate can be negative — no validation on sign.
 */
public record FundingEvent(Instant time, BigDecimal rate) {

  /** Validates non-null fields. */
  public FundingEvent {
    Objects.requireNonNull(time, "time");
    Objects.requireNonNull(rate, "rate");
  }
}
