package app.viglide.examples.macdtrend;

import app.viglide.core.params.CliArgs;
import app.viglide.core.spi.StrategyKind;
import app.viglide.core.spi.StrategyMetadata;
import app.viglide.core.spi.StrategyProvider;
import app.viglide.core.spi.StrategyStatus;
import app.viglide.core.spi.TradingStrategy;
import java.util.Map;

/**
 * {@link StrategyProvider} for {@code macdtrend} (PLAN-018 R-2.3) — replaces the {@code macdtrend}
 * branch of the now-dissolved {@code StrategyFactory}. Registered via {@code META-INF/services}.
 */
public final class TrendFollowMacdAtrStrategyProvider implements StrategyProvider {

  @Override
  public String name() {
    return "macdtrend";
  }

  @Override
  public StrategyMetadata metadata() {
    return new StrategyMetadata(
        "macdtrend",
        "1.0",
        "Trend-following strategy on MACD crossover with an ATR-active filter.",
        StrategyKind.OHLCV,
        StrategyStatus.BENCHMARK_ONLY);
  }

  @Override
  public TradingStrategy create(Map<String, String> args) {
    MacdTrendParameters d = MacdTrendParameters.textbookDefaults();
    return new TrendFollowMacdAtrStrategy(
        new MacdTrendParameters(
            CliArgs.intOpt(args, "macd-fast", d.macdFast()),
            CliArgs.intOpt(args, "macd-slow", d.macdSlow()),
            CliArgs.intOpt(args, "macd-signal", d.macdSignal()),
            CliArgs.intOpt(args, "atr-period", d.atrPeriod()),
            CliArgs.intOpt(args, "atr-lookback", d.atrLookback()),
            CliArgs.doubleOpt(args, "atr-active-ratio", d.atrActiveRatio()),
            CliArgs.doubleOpt(args, "histogram-scale", d.histogramScale())));
  }
}
