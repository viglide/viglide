package app.viglide.core.risk;

import java.util.List;

/**
 * Reconciles locally-tracked portfolio state against the exchange's own account record (PRD NFR-5)
 * — catches drift from a missed fill, manual intervention on the exchange, or a crash/restart that
 * resumed from a stale snapshot.
 *
 * <p>PUBLIC interface, no implementation in Phase 1: PLAN-006 is paper-trading only (no real orders
 * exist to drift from), so there is nothing yet to reconcile against. A future plan implements this
 * against Binance's real account/position endpoints once live orders exist.
 */
public interface ReconciliationPort {

  /**
   * Compares {@code localState} against the exchange's current account state.
   *
   * @return human-readable discrepancy descriptions; empty when everything matches
   */
  List<String> reconcile(PortfolioState localState);
}
