# Viglide

Transparent, hybrid cryptocurrency trading platform.

A fast, deterministic Java statistical engine generates trade signals. An optional, asynchronous AI debate layer adds context for ambiguous or high-stakes cases. A mandatory Risk Manager is the final gate before execution. Every decision is explainable and surfaced to the user.

This repository is the **public core** of Viglide — the domain model, indicator math, the
backtesting/calibration framework, the risk-management SPI and reference implementation, and a set
of textbook example strategies. The production trading strategies, tuned parameters, and the live
execution runtime are proprietary and live in a private repository, which consumes this one as an
ordinary Maven dependency.

**The bundled example strategies are unoptimized textbook references, not a recommendation** — see
[`BENCHMARKS.md`](BENCHMARKS.md) for the honest evidence, and
[`DISCLAIMER.md`](DISCLAIMER.md) before running anything against real funds.

## Modules in this repository

| Module | Purpose |
|---|---|
| `viglide-core` | Domain models, ports/SPI interfaces (`StrategyProvider`/`StrategyRegistry`, `ParameterSpaceProvider`), indicator math primitives (EMA, RSI, MACD, ATR, Bollinger), the backtesting framework, and a reference Risk Manager. |
| `viglide-research` | Backtest/calibrate/promote/portfolio CLIs and the calibration harness (purged K-fold with embargo, PSR/DSR, a circular-permutation null model). |
| `viglide-examples` | Textbook benchmark strategies (`emarsi`, `meanrev`, `macdtrend`) and ensemble combiners — see `BENCHMARKS.md`. |

## Build

```bash
./gradlew build            # compile all modules, run all tests
./gradlew test --parallel  # run tests in parallel
./gradlew :viglide-core:test    # test a single module
```

**Requirements:** Java 25 (Temurin; the build auto-provisions the toolchain via the Foojay
resolver), Gradle 9.5.1 (wrapper included).

## CLI tools

```bash
./gradlew :viglide-research:backtest  --args="--strategy=emarsi --dataset=<path> --symbol=BTCUSDT --interval=ONE_HOUR"
./gradlew :viglide-research:calibrate --args="--strategy=emarsi --dataset=<path> --search=grid --folds=5"
```

See `viglide-research/src/main/java/app/viglide/research/cli/` for the full argument lists —
each CLI class carries its own Javadoc.

## Stack

Java 25.0.3 · Gradle 9.5.1 (Kotlin DSL)

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md) — DCO sign-off (`git commit -s`), no CLA, Apache-2.0
inbound = outbound.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
