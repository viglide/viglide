# Benchmarks: what the bundled strategies actually are

`viglide-examples` ships four textbook strategies (`emarsi`, `meanrev`, `macdtrend`, and a
Bollinger-reversion variant) plus two ensemble combiners. This document exists so nobody mistakes
their presence for an endorsement.

## Status: `BENCHMARK_ONLY`

Every strategy in this module is tagged `StrategyStatus.BENCHMARK_ONLY` (`viglide-core`'s
`StrategyMetadata`). Concretely, that means:

- They are **unoptimized reference implementations** of published technical-analysis techniques
  (EMA crossover + RSI filter, mean reversion, MACD trend-following) — not the maintainer's
  production strategy, which is proprietary and lives in a private repository.
- `PaperTradingRunner` **refuses to run any `BENCHMARK_ONLY` strategy** in `--mode=testnet` or
  live order-placing modes. They exist for the backtest/calibration harness and as worked
  examples of the `TradingStrategy` SPI — nothing more.
- They were **not tuned or cherry-picked for good results.** Their parameters are the textbook
  defaults for each technique.

## What the evidence actually says

An ablation run against this family (12 pairs × 5 years, calibrated parameters vs. textbook
defaults, dated 2026-07-22) found that **neither "calibration adds value" nor "calibration is
unneeded" was supported** — most of the baseline's apparent wins came from years with too few
trades to draw a statistical conclusion from at all. That is the honest state of the evidence,
not a placeholder for a more flattering number to be filled in later.

No Sharpe ratio, return figure, or win rate for these strategies is published here. Earlier
internal headline numbers for this project were found to rest on a statistic (annualized Sharpe
computed over all days, including idle ones) that is mathematically decoupled from return
magnitude — a curve with two profitable trades in a year could score a "perfect" risk-adjusted
result. Rather than publish numbers under a since-retracted methodology, this file states the
methodological finding and lets you compute your own.

## Reproduce it yourself

```bash
./gradlew :viglide-research:backtest --args="--strategy=emarsi --dataset=<path> --symbol=BTCUSDT --interval=ONE_HOUR"
./gradlew :viglide-research:calibrate --args="--strategy=emarsi --dataset=<path> --search=grid --folds=5"
```

`viglide-research`'s calibration harness (purged K-fold with embargo, PSR/DSR, a circular-
permutation null model) is the same one used internally — see the CLI Javadoc under
`viglide-research/src/main/java/app/viglide/research/cli/` for the full flag list. Historical
OHLCV/funding data is not bundled with this repository; Binance's public REST endpoints are one
source.
