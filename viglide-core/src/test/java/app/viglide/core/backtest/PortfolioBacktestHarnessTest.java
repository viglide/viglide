package app.viglide.core.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.Factor;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.indicator.IndicatorMath;
import app.viglide.core.risk.ExecutionDecision;
import app.viglide.core.risk.PortfolioState;
import app.viglide.core.risk.RiskManagerPort;
import app.viglide.core.risk.RiskParameters;
import app.viglide.core.spi.StrategyMetadata;
import app.viglide.core.spi.TradingStrategy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * PLAN-008 Task H: circuit-breaker, aggregate-leverage, and determinism tests for {@link
 * PortfolioBacktestHarness} — the properties a per-symbol harness structurally cannot exercise.
 */
class PortfolioBacktestHarnessTest {

  private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");

  // ── Fakes ────────────────────────────────────────────────────────────────────────────────────

  /** Emits BUY on every evaluation, regardless of current position state. */
  private static final class AlwaysBuyStrategy implements TradingStrategy {
    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
      return Optional.of(
          new TechnicalSignal(
              ctx.symbol(),
              Direction.BUY,
              0.9,
              List.of(new Factor("TEST", "always-buy", 1.0)),
              "always buy",
              ctx.asOf()));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("AlwaysBuy", "0.0.1", "test-only");
    }
  }

  /** BUY on the first evaluation only, HOLD forever after. */
  private static final class BuyOnceStrategy implements TradingStrategy {
    private boolean bought;

    @Override
    public Optional<TechnicalSignal> evaluate(MarketContext ctx) {
      Direction d = bought ? Direction.HOLD : Direction.BUY;
      bought = true;
      return Optional.of(
          new TechnicalSignal(
              ctx.symbol(),
              d,
              0.9,
              List.of(new Factor("TEST", "buy-once", 1.0)),
              "buy once",
              ctx.asOf()));
    }

    @Override
    public StrategyMetadata metadata() {
      return new StrategyMetadata("BuyOnce", "0.0.1", "test-only");
    }
  }

  /**
   * Approves BUY at a fixed size per symbol unless the circuit breaker is tripped, in which case it
   * refuses exactly like the real {@code RiskManager}'s own CB check (step 2 of its gate order) —
   * the harness relies on {@code RiskManagerPort} implementations doing this, not on any
   * special-casing of its own (see {@link PortfolioBacktestHarness}'s class Javadoc).
   */
  private static final class FixedRiskManagerPort implements RiskManagerPort {
    private final Map<String, BigDecimal> sizeBySymbol;

    FixedRiskManagerPort(Map<String, BigDecimal> sizeBySymbol) {
      this.sizeBySymbol = sizeBySymbol;
    }

    @Override
    public ExecutionDecision gate(TechnicalSignal signal, PortfolioState state, MarketContext ctx) {
      if (state.circuitBreakerTripped()) {
        return new ExecutionDecision.Refuse(
            signal.symbol(),
            ExecutionDecision.RefusalReason.CIRCUIT_BREAKER_TRIPPED,
            "circuit breaker tripped",
            signal.asOf());
      }
      BigDecimal markPrice = ctx.candles().getLast().close();
      BigDecimal slDistance = BigDecimal.ONE;
      BigDecimal stopLoss =
          signal.direction() == Direction.BUY
              ? markPrice.subtract(slDistance)
              : markPrice.add(slDistance);
      BigDecimal size = sizeBySymbol.get(signal.symbol());
      return new ExecutionDecision.Execute(
          signal.symbol(),
          signal.direction(),
          size,
          size.multiply(markPrice, IndicatorMath.MC),
          stopLoss,
          slDistance,
          Optional.empty(),
          "stub execute",
          List.of(),
          signal.asOf());
    }

    @Override
    public RiskParameters riskParameters() {
      return RiskParameters.defaults();
    }
  }

  /**
   * Approves BUY at {@code requestedFraction × equity} notional, refusing with {@code
   * LEVERAGE_CAP_EXCEEDED} once aggregate open notional (across every symbol — read from {@code
   * state.totalOpenNotional()}) would exceed {@code maxLeverage × equity}. Mirrors the real {@code
   * RiskManager}'s own leverage check (step 9), just without the ATR-sizing machinery, so the
   * requested notional is exactly {@code requestedFraction} regardless of price.
   */
  private static final class LeverageCheckingRiskManagerPort implements RiskManagerPort {
    private final BigDecimal requestedFraction;

    LeverageCheckingRiskManagerPort(BigDecimal requestedFraction) {
      this.requestedFraction = requestedFraction;
    }

    @Override
    public ExecutionDecision gate(TechnicalSignal signal, PortfolioState state, MarketContext ctx) {
      BigDecimal markPrice = ctx.candles().getLast().close();
      BigDecimal requestedNotional = state.equity().multiply(requestedFraction, IndicatorMath.MC);
      BigDecimal maxNotional =
          state.equity().multiply(riskParameters().maxLeverage(), IndicatorMath.MC);
      BigDecimal totalNotional = state.totalOpenNotional().add(requestedNotional, IndicatorMath.MC);
      if (totalNotional.compareTo(maxNotional) > 0) {
        return new ExecutionDecision.Refuse(
            signal.symbol(),
            ExecutionDecision.RefusalReason.LEVERAGE_CAP_EXCEEDED,
            "total notional " + totalNotional + " would exceed " + maxNotional,
            signal.asOf());
      }
      BigDecimal size = requestedNotional.divide(markPrice, IndicatorMath.MC);
      BigDecimal slDistance = BigDecimal.ONE;
      return new ExecutionDecision.Execute(
          signal.symbol(),
          Direction.BUY,
          size,
          requestedNotional,
          markPrice.subtract(slDistance),
          slDistance,
          Optional.empty(),
          "stub leverage-checking execute",
          List.of(),
          signal.asOf());
    }

    @Override
    public RiskParameters riskParameters() {
      return RiskParameters.defaults();
    }
  }

  // ── Tests ────────────────────────────────────────────────────────────────────────────────────

  @Test
  void circuitBreaker_tripsOnGapDown_liquidatesNextBar_refusesEverythingAfter() {
    // AAA holds 95% of equity and gaps down 20% at t1 -> ~19% portfolio drawdown, over the 15%
    // threshold. BBB holds a token position so "both positions liquidated" is actually exercised.
    Map<String, List<Candle>> candles = new LinkedHashMap<>();
    candles.put(
        "AAA",
        List.of(
            candle(T0, 100, 100),
            candle(T0.plusSeconds(3600), 100, 80), // 20% gap down, marked at this bar's close
            candle(T0.plusSeconds(7200), 78, 78), // CB already tripped -> liquidated at this open
            candle(T0.plusSeconds(10800), 78, 78)));
    candles.put(
        "BBB",
        List.of(
            candle(T0, 100, 100),
            candle(T0.plusSeconds(3600), 100, 100),
            candle(T0.plusSeconds(7200), 100, 100),
            candle(T0.plusSeconds(10800), 100, 100)));

    Map<String, TradingStrategy> strategies =
        Map.of("AAA", new AlwaysBuyStrategy(), "BBB", new AlwaysBuyStrategy());
    Map<String, BigDecimal> sizes = Map.of("AAA", new BigDecimal("95"), "BBB", new BigDecimal("1"));
    FixedRiskManagerPort rm = new FixedRiskManagerPort(sizes);
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 1, new BigDecimal("1.0"), null, null, 8760);

    BacktestResult result =
        PortfolioBacktestHarness.run(
            candles, strategies, Map.of(), CandleInterval.ONE_HOUR, cfg, rm);

    // Both positions liquidated exactly once each, both as STOP_LOSS (the CB liquidation reason),
    // and nothing traded again afterwards.
    assertThat(result.tradeCount()).isEqualTo(2);
    assertThat(result.trades())
        .allSatisfy(t -> assertThat(t.exitReason()).isEqualTo(ExitReason.STOP_LOSS));

    // Every gate call from the trip onward (2 symbols x 2 remaining bars) is refused, all with
    // CIRCUIT_BREAKER_TRIPPED.
    assertThat(result.refusals()).hasSize(4);
    assertThat(result.refusals())
        .allSatisfy(
            r ->
                assertThat(r.reason())
                    .isEqualTo(ExecutionDecision.RefusalReason.CIRCUIT_BREAKER_TRIPPED));
    assertThat(result.refusals())
        .extracting(Refusal::symbol)
        .containsExactlyInAnyOrder("AAA", "AAA", "BBB", "BBB");
  }

  @Test
  void aggregateLeverage_secondSymbolRequestingSameFractionIsRefused() {
    Map<String, List<Candle>> candles = new LinkedHashMap<>();
    candles.put("AAA", List.of(candle(T0, 100, 100)));
    candles.put("BBB", List.of(candle(T0, 100, 100)));

    Map<String, TradingStrategy> strategies =
        Map.of("AAA", new BuyOnceStrategy(), "BBB", new BuyOnceStrategy());
    LeverageCheckingRiskManagerPort rm = new LeverageCheckingRiskManagerPort(new BigDecimal("1.2"));
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"), FeeModel.zero(), 1, new BigDecimal("1.0"), null, null, 8760);

    BacktestResult result =
        PortfolioBacktestHarness.run(
            candles, strategies, Map.of(), CandleInterval.ONE_HOUR, cfg, rm);

    // AAA's 1.2x-equity request is within the 2x cap (aggregate so far: 0). BBB's identical
    // request would bring the aggregate to 2.4x -- over the cap -- so only BBB is refused, and
    // only AAA's position exists to be closed at end-of-data.
    assertThat(result.refusals()).hasSize(1);
    assertThat(result.refusals().get(0).reason())
        .isEqualTo(ExecutionDecision.RefusalReason.LEVERAGE_CAP_EXCEEDED);
    assertThat(result.refusals().get(0).symbol()).isEqualTo("BBB");
    assertThat(result.tradeCount()).isEqualTo(1);
  }

  @Test
  void determinism_sameInputsProduceIdenticalResults() {
    Map<String, List<Candle>> candles = new LinkedHashMap<>();
    candles.put("AAA", series(30, 100.0, 0.05));
    candles.put("BBB", series(30, 50.0, 0.03));

    Map<String, BigDecimal> sizes = Map.of("AAA", new BigDecimal("1"), "BBB", new BigDecimal("2"));
    BacktestConfig cfg =
        new BacktestConfig(
            new BigDecimal("10000"),
            FeeModel.binanceDefault(),
            5,
            new BigDecimal("1.0"),
            null,
            null,
            8760);

    BacktestResult r1 =
        PortfolioBacktestHarness.run(
            candles,
            Map.of("AAA", new AlwaysBuyStrategy(), "BBB", new AlwaysBuyStrategy()),
            Map.of(),
            CandleInterval.ONE_HOUR,
            cfg,
            new FixedRiskManagerPort(sizes));
    BacktestResult r2 =
        PortfolioBacktestHarness.run(
            candles,
            Map.of("AAA", new AlwaysBuyStrategy(), "BBB", new AlwaysBuyStrategy()),
            Map.of(),
            CandleInterval.ONE_HOUR,
            cfg,
            new FixedRiskManagerPort(sizes));

    assertThat(r1).isEqualTo(r2);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

  private static Candle candle(Instant t, double open, double close) {
    double hi = Math.max(open, close) + 0.5;
    double lo = Math.min(open, close) - 0.5;
    return new Candle(
        t,
        BigDecimal.valueOf(open),
        BigDecimal.valueOf(hi),
        BigDecimal.valueOf(lo),
        BigDecimal.valueOf(close),
        BigDecimal.valueOf(1000));
  }

  /** A flat-open/close series drifting gently upward by {@code step} per bar. */
  private static List<Candle> series(int count, double startClose, double step) {
    List<Candle> out = new java.util.ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      double close = startClose + i * step;
      out.add(candle(T0.plusSeconds(3600L * i), close, close));
    }
    return out;
  }
}
