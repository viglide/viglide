package app.viglide.core.risk;

import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;

/**
 * Layer-4 Risk Manager SPI (PLAN-005b, ADR-0006, CLAUDE.md §4). Every order must pass through this
 * gate before being submitted to the exchange; no code path may place an order while bypassing it
 * (CLAUDE.md §11).
 *
 * <p>The concrete implementation lives in {@code app.viglide.core.risk.RiskManager}. The interface
 * is defined here in {@code viglide-core} (PUBLIC) so the backtester can wire it without depending
 * on the runtime module.
 */
public interface RiskManagerPort {

  /**
   * Gates a strategy signal against the hard limits in CLAUDE.md §7.
   *
   * <p>Returns {@link ExecutionDecision.Execute} when all limits pass, with RM-derived size and
   * mandatory stop-loss. Returns {@link ExecutionDecision.Refuse} when any limit would be breached,
   * with a {@link ExecutionDecision.RefusalReason} code for the audit trail.
   *
   * <p>Implementations must be deterministic: identical {@code (signal, state, ctx)} inputs must
   * produce identical outputs (NFR-7).
   *
   * @param signal Layer-1 signal from the strategy or combiner
   * @param state current portfolio snapshot (cash, equity, peak equity, open positions, CB flag)
   * @param ctx market context used for ATR and daily-volume computation
   */
  ExecutionDecision gate(TechnicalSignal signal, PortfolioState state, MarketContext ctx);

  /** Exposes the configured hard limits so the backtester can read the drawdown threshold. */
  RiskParameters riskParameters();
}
