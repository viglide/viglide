package app.viglide.core.domain;

import java.time.Duration;

/**
 * Sampling interval of the candle series (PRD §6). Extended beyond the original 1m/1h pair for
 * PLAN-009's cadence study (D9-3: 1m is the base data layer; 5m/15m/4h/1d are derived
 * aggregations). {@link #duration()} is the single source of truth for any interval-dependent
 * arithmetic — see {@code CandleInterval} usages flagged in the PLAN-009 Task B0 audit
 * (`docs/notes/2026-07-14-plan009-verdict.md`) for why most existing code does <em>not</em> need
 * one (annualisation resamples to daily first; funding accrual is timestamp-driven).
 */
public enum CandleInterval {
  ONE_MINUTE(Duration.ofMinutes(1)),
  FIVE_MINUTES(Duration.ofMinutes(5)),
  FIFTEEN_MINUTES(Duration.ofMinutes(15)),
  ONE_HOUR(Duration.ofHours(1)),
  FOUR_HOURS(Duration.ofHours(4)),
  ONE_DAY(Duration.ofDays(1));

  private final Duration duration;

  CandleInterval(Duration duration) {
    this.duration = duration;
  }

  /** Wall-clock length of one bar at this interval. */
  public Duration duration() {
    return duration;
  }
}
