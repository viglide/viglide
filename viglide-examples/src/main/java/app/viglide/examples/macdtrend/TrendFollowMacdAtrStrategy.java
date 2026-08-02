package app.viglide.examples.macdtrend;

import app.viglide.core.domain.Candle;
import app.viglide.core.domain.Direction;
import app.viglide.core.domain.Factor;
import app.viglide.core.domain.MarketContext;
import app.viglide.core.domain.TechnicalSignal;
import app.viglide.core.indicator.AtrCalculator;
import app.viglide.core.indicator.IndicatorMath;
import app.viglide.core.indicator.IndicatorSeries;
import app.viglide.core.indicator.MacdCalculator;
import app.viglide.core.indicator.MacdSeries;
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
 * <p>Trend-following strategy on MACD line/signal crossover, filtered by an ATR-based "active
 * volatility" gate (PLAN-003 §C.1). Trades only when both the crossover happens and the current ATR
 * is at or above its trailing baseline — the filter suppresses signals fired in dead drift regimes
 * where transactional cost dominates.
 */
public final class TrendFollowMacdAtrStrategy implements TradingStrategy {

  private final MacdTrendParameters params;

  public TrendFollowMacdAtrStrategy(MacdTrendParameters params) {
    this.params = Objects.requireNonNull(params, "params");
  }

  @Override
  public Optional<TechnicalSignal> evaluate(MarketContext context) {
    List<Candle> candles = context.candles();
    int macdRequired = params.macdSlow() + params.macdSignal();
    int atrRequired = params.atrPeriod() + params.atrLookback() + 1;
    if (candles.size() < Math.max(macdRequired, atrRequired)) {
      return Optional.empty();
    }

    List<BigDecimal> closes = candles.stream().map(Candle::close).toList();
    MacdSeries macd =
        MacdCalculator.calculate(closes, params.macdFast(), params.macdSlow(), params.macdSignal());
    IndicatorSeries atr = AtrCalculator.calculate(candles, params.atrPeriod());

    if (macd.macd().size() < 2 || atr.values().size() < params.atrLookback() + 1) {
      return Optional.empty();
    }

    BigDecimal macdNow = macd.lastMacd();
    BigDecimal macdPrev = macd.previousMacd();
    BigDecimal signalNow = macd.lastSignal();
    BigDecimal signalPrev = macd.previousSignal();

    boolean bullishCross = macdPrev.compareTo(signalPrev) <= 0 && macdNow.compareTo(signalNow) > 0;
    boolean bearishCross = macdPrev.compareTo(signalPrev) >= 0 && macdNow.compareTo(signalNow) < 0;

    // ATR active gate: current ATR vs mean of the previous `atrLookback` values.
    List<BigDecimal> atrVals = atr.values();
    BigDecimal atrNow = atrVals.getLast();
    BigDecimal sum = BigDecimal.ZERO;
    int from = atrVals.size() - 1 - params.atrLookback();
    for (int i = from; i < atrVals.size() - 1; i++) {
      sum = sum.add(atrVals.get(i));
    }
    BigDecimal atrAvg = sum.divide(BigDecimal.valueOf(params.atrLookback()), IndicatorMath.MC);
    BigDecimal threshold =
        atrAvg.multiply(BigDecimal.valueOf(params.atrActiveRatio()), IndicatorMath.MC);
    boolean atrActive = atrNow.compareTo(threshold) >= 0;

    Direction direction;
    if (bullishCross && atrActive) {
      direction = Direction.BUY;
    } else if (bearishCross && atrActive) {
      direction = Direction.SELL;
    } else {
      direction = Direction.HOLD;
    }

    // Confidence: histogram magnitude relative to lastClose × histogramScale (dimensionless).
    BigDecimal lastClose = closes.getLast();
    BigDecimal scale =
        lastClose
            .abs()
            .multiply(BigDecimal.valueOf(params.histogramScale()), IndicatorMath.MC)
            .max(BigDecimal.ONE);
    BigDecimal histAbs = macd.lastHistogram().abs();
    double histRatio = clamp01(histAbs.divide(scale, IndicatorMath.MC).doubleValue());
    double atrRoom = computeAtrRoom(atrNow, atrAvg);
    double confidence =
        switch (direction) {
          case BUY, SELL -> clamp01(0.5 + 0.5 * histRatio);
          case HOLD -> clamp01(0.5 * histRatio);
        };

    String macdDetail =
        String.format(
            "MACD=%.4f, Signal=%.4f, Histogram=%.4f → %s crossover",
            macdNow.doubleValue(),
            signalNow.doubleValue(),
            macd.lastHistogram().doubleValue(),
            bullishCross ? "bullish" : bearishCross ? "bearish" : "none");
    String atrDetail =
        String.format(
            "ATR(%d)=%.4f, avg=%.4f → %s",
            params.atrPeriod(),
            atrNow.doubleValue(),
            atrAvg.doubleValue(),
            atrActive ? "active" : "quiet");
    List<Factor> factors =
        List.of(
            new Factor("MACD_CROSS", macdDetail, histRatio),
            new Factor("ATR_REGIME", atrDetail, atrRoom));

    String explanation = buildExplanation(direction, context.symbol(), atrActive);

    return Optional.of(
        new TechnicalSignal(
            context.symbol(), direction, confidence, factors, explanation, context.asOf()));
  }

  @Override
  public StrategyMetadata metadata() {
    return new StrategyMetadata(
        "TrendFollowMacdAtr",
        "0.1.0",
        "MACD("
            + params.macdFast()
            + "/"
            + params.macdSlow()
            + "/"
            + params.macdSignal()
            + ") gated by ATR("
            + params.atrPeriod()
            + ") active vs "
            + params.atrLookback()
            + "-bar baseline",
        StrategyKind.OHLCV,
        StrategyStatus.BENCHMARK_ONLY);
  }

  private static double computeAtrRoom(BigDecimal atrNow, BigDecimal atrAvg) {
    if (atrAvg.signum() <= 0) return atrNow.signum() > 0 ? 1.0 : 0.0;
    double ratio = atrNow.divide(atrAvg, IndicatorMath.MC).doubleValue();
    // Map [0.5, 1.5] linearly to [0, 1]; clamp outside.
    return Math.clamp((ratio - 0.5) / 1.0, 0.0, 1.0);
  }

  private String buildExplanation(Direction direction, String symbol, boolean atrActive) {
    return switch (direction) {
      case BUY ->
          String.format(
              "BUY %s: MACD crossed above signal in an active volatility regime.", symbol);
      case SELL ->
          String.format(
              "SELL %s: MACD crossed below signal in an active volatility regime.", symbol);
      case HOLD ->
          atrActive
              ? String.format("HOLD %s: no MACD crossover.", symbol)
              : String.format("HOLD %s: volatility too low for trend signals.", symbol);
    };
  }

  private static double clamp01(double v) {
    return Math.clamp(v, 0.0, 1.0);
  }
}
