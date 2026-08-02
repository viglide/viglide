package app.viglide.examples.meanrev;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.Factor;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.indicator.BollingerCalculator;
import app.viglide.core.indicator.BollingerSeries;
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
 * <p>Mean-reversion strategy: BUY when close touches the lower Bollinger band <strong>and</strong>
 * RSI is oversold; SELL when close touches the upper band <strong>and</strong> RSI is overbought
 * (PLAN-003 §B). Designed as the chop-regime complement to the trend-following EMA crossover — the
 * two strategies win in opposite regimes by construction.
 *
 * <p><strong>Confidence heuristic</strong> — POC composite of how deep the close pierced the band
 * and how extreme the RSI got. Real calibrated coefficients live in Secret Manager (PRD §9.3).
 */
public final class MeanReversionRsiBbStrategy implements TradingStrategy {

  private final MeanRevParameters params;

  public MeanReversionRsiBbStrategy(MeanRevParameters params) {
    this.params = Objects.requireNonNull(params, "params");
  }

  @Override
  public Optional<TechnicalSignal> evaluate(MarketContext context) {
    List<Candle> candles = context.candles();
    int minRequired = Math.max(params.rsiPeriod() + 1, params.bbPeriod());
    if (candles.size() < minRequired) {
      return Optional.empty();
    }

    List<BigDecimal> closes = candles.stream().map(Candle::close).toList();
    IndicatorSeries rsi = RsiCalculator.calculate(closes, params.rsiPeriod());
    BollingerSeries bb =
        BollingerCalculator.calculate(closes, params.bbPeriod(), params.bbStdevK());

    BigDecimal lastClose = closes.getLast();
    BigDecimal lastUpper = bb.lastUpper();
    BigDecimal lastLower = bb.lastLower();
    BigDecimal lastMiddle = bb.lastMiddle();
    BigDecimal bandWidth = lastUpper.subtract(lastLower);
    BigDecimal tolerance =
        bandWidth.multiply(BigDecimal.valueOf(params.bandTouchTolerance()), IndicatorMath.MC);
    BigDecimal bandWidthSafe = bandWidth.signum() == 0 ? BigDecimal.ONE : bandWidth;

    boolean lowTouch = lastClose.compareTo(lastLower.add(tolerance)) <= 0;
    boolean highTouch = lastClose.compareTo(lastUpper.subtract(tolerance)) >= 0;
    double lastRsi = rsi.last().doubleValue();

    Direction direction;
    if (lowTouch && lastRsi < params.rsiBuyThreshold()) {
      direction = Direction.BUY;
    } else if (highTouch && lastRsi > params.rsiSellThreshold()) {
      direction = Direction.SELL;
    } else {
      direction = Direction.HOLD;
    }

    double bandDepth;
    double rsiExtreme;
    switch (direction) {
      case BUY -> {
        BigDecimal underBand = lastLower.add(tolerance).subtract(lastClose);
        bandDepth = clamp01(underBand.divide(bandWidthSafe, IndicatorMath.MC).doubleValue());
        rsiExtreme = clamp01((params.rsiBuyThreshold() - lastRsi) / params.rsiBuyThreshold());
      }
      case SELL -> {
        BigDecimal overBand = lastClose.subtract(lastUpper.subtract(tolerance));
        bandDepth = clamp01(overBand.divide(bandWidthSafe, IndicatorMath.MC).doubleValue());
        rsiExtreme =
            clamp01((lastRsi - params.rsiSellThreshold()) / (100.0 - params.rsiSellThreshold()));
      }
      default -> {
        bandDepth = 0.0;
        rsiExtreme = 0.0;
      }
    }
    double confidence =
        direction == Direction.HOLD ? 0.0 : clamp01(0.5 + 0.25 * bandDepth + 0.25 * rsiExtreme);

    String bbDetail =
        String.format(
            "Close=%.4f, Lower=%.4f, Middle=%.4f, Upper=%.4f → %s",
            lastClose.doubleValue(),
            lastLower.doubleValue(),
            lastMiddle.doubleValue(),
            lastUpper.doubleValue(),
            direction == Direction.BUY
                ? "touched lower band"
                : direction == Direction.SELL ? "touched upper band" : "inside band");
    String rsiDetail =
        String.format(
            "RSI(%d)=%.2f, thresholds %.0f/%.0f → %s",
            params.rsiPeriod(),
            lastRsi,
            params.rsiBuyThreshold(),
            params.rsiSellThreshold(),
            direction == Direction.BUY
                ? "oversold"
                : direction == Direction.SELL ? "overbought" : "neutral");
    List<Factor> factors =
        List.of(
            new Factor("BB_TOUCH", bbDetail, bandDepth),
            new Factor("RSI_EXTREME", rsiDetail, rsiExtreme));

    String explanation = buildExplanation(direction, context.symbol(), lastRsi);

    return Optional.of(
        new TechnicalSignal(
            context.symbol(), direction, confidence, factors, explanation, context.asOf()));
  }

  @Override
  public StrategyMetadata metadata() {
    return new StrategyMetadata(
        "MeanReversionRsiBb",
        "0.1.0",
        "BB("
            + params.bbPeriod()
            + ","
            + params.bbStdevK()
            + ") band-touch gated by RSI("
            + params.rsiPeriod()
            + ")",
        StrategyKind.OHLCV,
        StrategyStatus.BENCHMARK_ONLY);
  }

  private String buildExplanation(Direction direction, String symbol, double rsi) {
    return switch (direction) {
      case BUY ->
          String.format(
              "BUY %s: price reverted to lower Bollinger band with RSI %.1f below %.0f.",
              symbol, rsi, params.rsiBuyThreshold());
      case SELL ->
          String.format(
              "SELL %s: price extended to upper Bollinger band with RSI %.1f above %.0f.",
              symbol, rsi, params.rsiSellThreshold());
      case HOLD -> String.format("HOLD %s: price inside Bollinger band, RSI neutral.", symbol);
    };
  }

  private static double clamp01(double v) {
    return Math.clamp(v, 0.0, 1.0);
  }
}
