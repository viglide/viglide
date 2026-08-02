package app.viglide.examples.emarsi;

import app.viglide.core.params.CliArgs;
import app.viglide.core.spi.StrategyKind;
import app.viglide.core.spi.StrategyMetadata;
import app.viglide.core.spi.StrategyProvider;
import app.viglide.core.spi.StrategyStatus;
import app.viglide.core.spi.TradingStrategy;
import java.util.Map;

/**
 * {@link StrategyProvider} for {@code emarsi} (PLAN-018 R-2.3) — replaces the {@code emarsi} branch
 * of the now-dissolved {@code StrategyFactory}. Registered via {@code META-INF/services}.
 */
public final class EmaCrossoverRsiStrategyProvider implements StrategyProvider {

  @Override
  public String name() {
    return "emarsi";
  }

  @Override
  public StrategyMetadata metadata() {
    return new StrategyMetadata(
        "emarsi",
        "1.0",
        "EMA crossover strategy gated by an RSI filter.",
        StrategyKind.OHLCV,
        StrategyStatus.BENCHMARK_ONLY);
  }

  @Override
  public TradingStrategy create(Map<String, String> args) {
    StrategyParameters d = StrategyParameters.textbookDefaults();
    return new EmaCrossoverRsiStrategy(
        new StrategyParameters(
            CliArgs.intOpt(args, "ema-fast", d.emaFast()),
            CliArgs.intOpt(args, "ema-slow", d.emaSlow()),
            CliArgs.intOpt(args, "rsi-period", d.rsiPeriod()),
            CliArgs.doubleOpt(args, "rsi-overbought", d.rsiOverbought()),
            CliArgs.doubleOpt(args, "rsi-oversold", d.rsiOversold()),
            CliArgs.doubleOpt(args, "spread-scale", d.spreadScale())));
  }
}
