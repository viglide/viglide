# Contributing to Viglide

Thanks for your interest! This repository contains the **public core** of Viglide:

- `viglide-core` — domain types, indicator math, the backtesting framework, the risk-management
  SPI + reference implementation.
- `viglide-research` — backtest/calibrate/promote/portfolio CLIs and the calibration harness.
- `viglide-examples` — textbook benchmark strategies and ensemble combiners (see `BENCHMARKS.md`
  for their measured performance — they are reference implementations, not recommendations).

The production trading strategies and tuned parameters are proprietary, live in a private
repository, and are out of scope here — PRs adding tuned strategies will be declined.

This is a normal repository: accepted PRs are merged here, and your commit and authorship
are the project's history. A separate private repository builds proprietary strategies on top
of this library — it consumes `viglide-core` as an ordinary dependency and has no bearing on how
contributions here are handled.

## What this project is, and what the maintainer plans

No surprises, stated up front: the maintainer may build commercial services on top of this
code. **`viglide-core` stays Apache-2.0 permanently** — that is a commitment, not a current
intention. There is no CLA and no copyright assignment: you keep your copyright, and your
contribution is licensed to everyone (including the maintainer) under Apache-2.0, on exactly
the same terms as everyone else's.

Governance is **maintainer-led**. This is not a collectively governed project, and contributing
does not confer equity, revenue share, or a role — see the Apache-2.0 licence for exactly what
you grant and exactly what you keep. Reviews happen on a best-effort basis around a day job;
if a PR goes quiet, a polite ping is welcome and not rude.

## What's in scope

Welcome: indicator implementations and math fixes, backtesting/calibration-harness improvements,
additional example strategies, documentation, test coverage, performance work, build and CI
improvements, bug reports with a failing test.

Out of scope, and declined on sight regardless of quality — not a judgement on the work:
- Tuned strategy parameters or production strategy logic (proprietary, private repo).
- Anything requiring the private modules to build or run.
- New runtime dependencies in `viglide-core` — the zero-dependency core is a feature.
- Anything that weakens a Risk Manager invariant or makes the deterministic layer
  nondeterministic (see Ground rules).
- Donation, sponsorship, or exchange-referral links anywhere in the repository.

## Eligibility

The maintainer is subject to an employer conflict-of-interest disclosure that restricts who
may contribute. Contributions cannot be accepted from employees or contractors of the
maintainer's employer. If you think this might apply to you, email the maintainer before
opening a PR rather than guessing — a declined PR is nobody's idea of fun.

## Ground rules
- **License & sign-off.** Contributions are accepted under the Apache License 2.0
  (inbound = outbound, per Apache-2.0 §5). Every commit must carry a Developer Certificate of
  Origin sign-off: `git commit -s` (adds `Signed-off-by`). See https://developercertificate.org.
  There is **no CLA** — nothing to sign, nothing assigned.
- **Build.** `./gradlew build` must pass. Java 25 (Temurin; the build auto-provisions the
  toolchain). No new runtime dependencies in `viglide-core` without prior discussion in an issue —
  the zero-dependency core is a feature.
- **Style.** Google Java Style, enforced by Spotless (`./gradlew spotlessApply`). Records for
  immutable data; no Lombok; `BigDecimal` for money — never `double`; `Instant`/`Clock`
  injected, never wall-clock reads in domain logic.
- **Tests.** Every change ships with tests (JUnit 5 + AssertJ; jqwik for math properties).
  Deterministic and hermetic — no network, no sleeps, no wall clock.
- **Risk invariants are non-negotiable.** No PR may create a code path that places an order
  without passing the Risk Manager gate, weaken a hard limit, or make the deterministic layer
  nondeterministic. Such PRs will be declined regardless of other merits.

## Process
1. Open an issue describing the change before large PRs.
2. Small, focused PRs; conventional commit messages.
3. CI must be green; a maintainer reviews and merges.
