package app.viglide.examples.emarsi;

import app.viglide.core.domain.*;
import app.viglide.core.indicator.EmaCalculator;
import app.viglide.core.indicator.IndicatorMath;
import app.viglide.core.indicator.IndicatorSeries;
import app.viglide.core.indicator.RsiCalculator;
import app.viglide.core.spi.StrategyKind;
import app.viglide.core.spi.StrategyMetadata;
import app.viglide.core.spi.StrategyStatus;
import app.viglide.core.spi.TradingStrategy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Textbook benchmark strategy — a reference implementation of a published technique, deliberately
 * unoptimized. NOT a recommendation and NOT the Viglide production strategy set; see BENCHMARKS.md
 * for measured results. Educational use only — see DISCLAIMER.md.
 *
 * <p>EMA crossover strategy gated by an RSI filter (PRD §6 "first strategy").
 *
 * <p>Decision rule (ADR-0001 §3.2):
 *
 * <ul>
 *   <li>BUY — fast EMA crossed above slow EMA AND RSI is below overbought threshold.
 *   <li>SELL — fast EMA crossed below slow EMA AND RSI is above oversold threshold.
 *   <li>HOLD — no fresh crossover, or RSI filter blocked the signal.
 * </ul>
 *
 * <p>Returns {@link Optional#empty()} when the context has too few candles to evaluate (i.e. before
 * the slow EMA lookback window is fully seeded).
 *
 * <p><strong>Confidence heuristic</strong> — this POC uses a normalised EMA spread as a proxy for
 * signal strength (wider spread = more conviction). The real calibrated coefficients will come from
 * GCP Secret Manager, never from source (ADR-0001 §4, PRD §9.3).
 */
public final class EmaCrossoverRsiStrategy implements TradingStrategy {

  private final StrategyParameters params;

  /**
   * @param params configuration; must not be null
   */
  public EmaCrossoverRsiStrategy(StrategyParameters params) {
    this.params = Objects.requireNonNull(params, "params");
  }

  @Override
  public Optional<TechnicalSignal> evaluate(MarketContext context) {
    List<Candle> candles = context.candles();
    int size = candles.size();

    // Need at least emaSlow+1 candles (for a "previous" slow-EMA bar) and rsiPeriod+1
    if (size < params.emaSlow() + 1 || size < params.rsiPeriod() + 1) {
      return Optional.empty();
    }

    List<BigDecimal> closes = candles.stream().map(Candle::close).toList();

    IndicatorSeries fastEma = EmaCalculator.calculate(closes, params.emaFast());
    IndicatorSeries slowEma = EmaCalculator.calculate(closes, params.emaSlow());
    IndicatorSeries rsi = RsiCalculator.calculate(closes, params.rsiPeriod());

    BigDecimal fastNow = fastEma.last();
    BigDecimal fastPrev = fastEma.previous();
    BigDecimal slowNow = slowEma.last();
    BigDecimal slowPrev = slowEma.previous();
    double rsiNow = rsi.last().doubleValue();

    boolean bullishCross = fastPrev.compareTo(slowPrev) <= 0 && fastNow.compareTo(slowNow) > 0;
    boolean bearishCross = fastPrev.compareTo(slowPrev) >= 0 && fastNow.compareTo(slowNow) < 0;

    Direction direction;
    if (bullishCross && rsiNow < params.rsiOverbought()) {
      direction = Direction.BUY;
    } else if (bearishCross && rsiNow > params.rsiOversold()) {
      direction = Direction.SELL;
    } else {
      direction = Direction.HOLD;
    }

    // Normalised EMA spread drives confidence (POC heuristic — replace with calibrated model)
    BigDecimal spread = fastNow.subtract(slowNow).abs();
    BigDecimal divisor = slowNow.abs().max(BigDecimal.ONE); // guard against zero-price candles
    double relSpread = spread.divide(divisor, IndicatorMath.MC).doubleValue();
    double norm = Math.min(1.0, relSpread / params.spreadScale());
    double confidence =
        switch (direction) {
          case BUY, SELL -> clamp01(0.5 + 0.5 * norm);
          case HOLD -> clamp01(0.5 * norm);
        };

    // Factors surfaced to the user as "Signal Breakdown"
    String crossoverDetail =
        String.format(
            "EMA(%d)=%.4f, EMA(%d)=%.4f → %s crossover",
            params.emaFast(),
            fastNow.doubleValue(),
            params.emaSlow(),
            slowNow.doubleValue(),
            bullishCross ? "bullish" : bearishCross ? "bearish" : "none");

    double rsiRoom =
        switch (direction) {
          case BUY -> clamp01((params.rsiOverbought() - rsiNow) / params.rsiOverbought());
          case SELL -> clamp01((rsiNow - params.rsiOversold()) / (100.0 - params.rsiOversold()));
          case HOLD -> 0.0;
        };

    String rsiDetail =
        String.format(
            "RSI(%d)=%.2f, thresholds %.0f/%.0f → filter %s",
            params.rsiPeriod(),
            rsiNow,
            params.rsiOverbought(),
            params.rsiOversold(),
            direction != Direction.HOLD ? "PASSED" : "N/A");

    List<Factor> factors =
        List.of(
            new Factor("EMA_CROSSOVER", crossoverDetail, norm),
            new Factor("RSI_FILTER", rsiDetail, rsiRoom));

    String explanation = buildExplanation(direction, context.symbol(), rsiNow);

    return Optional.of(
        new TechnicalSignal(
            context.symbol(), direction, confidence, factors, explanation, context.asOf()));
  }

  @Override
  public StrategyMetadata metadata() {
    return new StrategyMetadata(
        "EmaCrossoverRsi",
        "0.1.0",
        "EMA("
            + params.emaFast()
            + "/"
            + params.emaSlow()
            + ") crossover gated by RSI("
            + params.rsiPeriod()
            + ")",
        StrategyKind.OHLCV,
        StrategyStatus.BENCHMARK_ONLY);
  }

  private String buildExplanation(Direction direction, String symbol, double rsiNow) {
    return switch (direction) {
      case BUY ->
          String.format(
              "BUY %s: fast EMA crossed above slow EMA with RSI %.1f below overbought %.0f.",
              symbol, rsiNow, params.rsiOverbought());
      case SELL ->
          String.format(
              "SELL %s: fast EMA crossed below slow EMA with RSI %.1f above oversold %.0f.",
              symbol, rsiNow, params.rsiOversold());
      case HOLD -> String.format("HOLD %s: no actionable EMA crossover at this bar.", symbol);
    };
  }

  private static double clamp01(double v) {
    return Math.clamp(v, 0.0, 1.0);
  }
}
