package app.viglide.examples.meanrev;

import app.viglide.core.params.CliArgs;
import app.viglide.core.spi.StrategyKind;
import app.viglide.core.spi.StrategyMetadata;
import app.viglide.core.spi.StrategyProvider;
import app.viglide.core.spi.StrategyStatus;
import app.viglide.core.spi.TradingStrategy;
import java.util.Map;

/**
 * {@link StrategyProvider} for {@code meanrev} (PLAN-018 R-2.3) — replaces the {@code meanrev}
 * branch of the now-dissolved {@code StrategyFactory}. Registered via {@code META-INF/services}.
 */
public final class MeanReversionRsiBbStrategyProvider implements StrategyProvider {

  @Override
  public String name() {
    return "meanrev";
  }

  @Override
  public StrategyMetadata metadata() {
    return new StrategyMetadata(
        "meanrev",
        "1.0",
        "Mean-reversion strategy on RSI + Bollinger band touch.",
        StrategyKind.OHLCV,
        StrategyStatus.BENCHMARK_ONLY);
  }

  @Override
  public TradingStrategy create(Map<String, String> args) {
    MeanRevParameters d = MeanRevParameters.textbookDefaults();
    return new MeanReversionRsiBbStrategy(
        new MeanRevParameters(
            CliArgs.intOpt(args, "rsi-period", d.rsiPeriod()),
            CliArgs.intOpt(args, "bb-period", d.bbPeriod()),
            CliArgs.doubleOpt(args, "bb-stdev-k", d.bbStdevK()),
            CliArgs.doubleOpt(args, "rsi-buy", d.rsiBuyThreshold()),
            CliArgs.doubleOpt(args, "rsi-sell", d.rsiSellThreshold()),
            CliArgs.doubleOpt(args, "band-touch-tolerance", d.bandTouchTolerance())));
  }
}
