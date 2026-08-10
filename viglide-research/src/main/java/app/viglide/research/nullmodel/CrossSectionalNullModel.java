package app.viglide.research.nullmodel;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.backtest.BacktestResult;
import app.viglide.core.backtest.PortfolioFundingArbHarnessV2;
import app.viglide.core.domain.Candle;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.domain.FundingEvent;
import app.viglide.core.risk.BacktestClockSync;
import app.viglide.core.risk.MutableClock;
import app.viglide.core.risk.RiskManager;
import app.viglide.core.risk.RiskManagerPort;
import app.viglide.core.risk.RiskParameters;
import app.viglide.core.spi.TradingStrategy;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * ADR-0016 condition 6 for a cross-sectional book (PLAN-023 Task B): run {@code N} matched-turnover
 * random-top-k baselines and report where the real strategy's result falls in their distribution.
 * The gate is "beats the null-model 95th percentile"; this produces the percentile.
 *
 * <p><strong>This binds on a negative result exactly as hard as on a positive one.</strong> "Worse
 * than a random basket of the same pairs" and "indistinguishable from one" are different findings,
 * and a NOT PROVEN verdict is entitled to whichever is true — a strategy that merely fails to beat
 * its baseline has a different meaning from one that is actively destroyed by its own turnover.
 *
 * <p>Every permutation runs through the same {@code PortfolioFundingArbHarnessV2} the real book
 * does, with a fresh {@link RiskManager} per permutation, so the baseline pays the same fees,
 * spreads and risk gating. A baseline evaluated under cheaper assumptions than the strategy is not
 * a baseline.
 */
public final class CrossSectionalNullModel {

  private CrossSectionalNullModel() {}

  /**
   * @param permutationReturns every permutation's total return, ascending
   * @param actualReturn the real strategy's total return over the same data
   * @param percentileOfActual share of permutations the strategy beat, in [0, 1] — compare against
   *     0.95 for ADR-0016 condition 6
   */
  public record NullModelOutcome(
      List<Double> permutationReturns,
      double actualReturn,
      double percentileOfActual,
      double p95,
      double median) {

    /** ADR-0016 condition 6 as a boolean: does the strategy clear the 95th percentile? */
    public boolean beatsP95() {
      return actualReturn > p95;
    }
  }

  /**
   * @param n number of permutations; ADR-0016 condition 6 pre-registers 200
   * @param seed base seed — permutation {@code i} uses {@code seed + i}, so the whole run is
   *     reproducible from one number (NFR-7)
   */
  public static NullModelOutcome run(
      Map<String, List<Candle>> perpBySymbol,
      Map<String, List<Candle>> spotBySymbol,
      Map<String, List<FundingEvent>> fundingBySymbol,
      CandleInterval interval,
      BacktestConfig cfg,
      RiskParameters riskParameters,
      int k,
      int minFundingEvents,
      int n,
      long seed,
      double actualReturn,
      Consumer<String> progress) {
    Objects.requireNonNull(perpBySymbol, "perpBySymbol");
    Objects.requireNonNull(fundingBySymbol, "fundingBySymbol");
    Objects.requireNonNull(riskParameters, "riskParameters");
    if (n < 1) {
      throw new IllegalArgumentException("n must be >= 1, got: " + n);
    }

    Instant start = Instant.now();
    List<Double> returns = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      Map<String, NavigableMap<Instant, Boolean>> membership =
          RandomTopKMembership.compute(fundingBySymbol, k, minFundingEvents, seed + i);
      Map<String, TradingStrategy> strategies = new LinkedHashMap<>();
      for (String symbol : perpBySymbol.keySet()) {
        strategies.put(
            symbol,
            new MembershipStrategy(
                symbol, membership.getOrDefault(symbol, new java.util.TreeMap<>()), "NullModel"));
      }
      MutableClock clock = new MutableClock(Instant.EPOCH, ZoneOffset.UTC);
      RiskManagerPort rm = new BacktestClockSync(new RiskManager(riskParameters, clock), clock);
      BacktestResult r =
          PortfolioFundingArbHarnessV2.run(
              perpBySymbol, spotBySymbol, fundingBySymbol, strategies, interval, cfg, rm);
      returns.add(r.totalReturn().doubleValue());
      if (progress != null && (i + 1) % 20 == 0) {
        progress.accept(
            String.format(
                "[%s] null model %d/%d elapsed=%ds",
                Instant.now(), i + 1, n, Duration.between(start, Instant.now()).toSeconds()));
      }
    }

    List<Double> sorted = new ArrayList<>(returns);
    sorted.sort(Double::compareTo);
    long beaten = sorted.stream().filter(v -> actualReturn > v).count();
    return new NullModelOutcome(
        List.copyOf(sorted),
        actualReturn,
        (double) beaten / sorted.size(),
        percentile(sorted, 0.95),
        percentile(sorted, 0.50));
  }

  /**
   * Nearest-rank percentile on an already-ascending list. Deliberately not interpolated: with N=200
   * the difference is immaterial, and a rank is easier to state honestly in a verdict note than an
   * interpolated value nobody can point at a permutation for.
   */
  static double percentile(List<Double> ascending, double p) {
    if (ascending.isEmpty()) {
      throw new IllegalArgumentException("no permutations");
    }
    int rank = (int) Math.ceil(p * ascending.size());
    return ascending.get(Math.min(Math.max(rank - 1, 0), ascending.size() - 1));
  }
}
