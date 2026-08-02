package app.viglide.core.backtest;

import app.viglide.core.domain.Direction;
import app.viglide.core.risk.ExecutionDecision;
import java.time.Instant;
import java.util.Objects;

/**
 * One Risk Manager refusal, recorded for the audit trail (F6, PLAN-007 Task D). PLAN-005b Task D
 * required the trade log to capture every decision the Risk Manager makes, not just executed trades
 * — a refusal reason without a corresponding record left operators unable to reconstruct why the RM
 * declined a specific bar.
 */
public record Refusal(
    Instant time,
    String symbol,
    Direction wantedSide,
    ExecutionDecision.RefusalReason reason,
    String explanation) {

  /** Validates required fields so every refusal is fully auditable. */
  public Refusal {
    Objects.requireNonNull(time, "time");
    Objects.requireNonNull(symbol, "symbol");
    Objects.requireNonNull(wantedSide, "wantedSide");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(explanation, "explanation");
    if (symbol.isBlank()) {
      throw new IllegalArgumentException("symbol must not be blank");
    }
  }
}
