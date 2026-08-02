package app.viglide.core.regime;

import java.time.YearMonth;
import java.util.Objects;

/**
 * One calendar month's funding/volatility regime labels for a pair (PLAN-009 Task H). {@code asOf}
 * is the last hourly candle's {@code openTime} used to compute the trailing windows for this month
 * — the audit trail for exactly which bar the labels were "as of".
 */
public record MonthlyRegime(
    YearMonth month, java.time.Instant asOf, FundingRegime funding, VolatilityRegime volatility) {

  public MonthlyRegime {
    Objects.requireNonNull(month, "month");
    Objects.requireNonNull(asOf, "asOf");
    Objects.requireNonNull(funding, "funding");
    Objects.requireNonNull(volatility, "volatility");
  }
}
