package app.viglide.research.calibrate;

import app.viglide.core.backtest.BacktestConfig;
import app.viglide.core.calibrate.Candidate;
import app.viglide.core.domain.CandleInterval;
import app.viglide.core.params.CliArgs;
import app.viglide.core.spi.StrategyRegistry;
import app.viglide.core.spi.TradingStrategy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Plateau-aware winner selection (PLAN-009 Task E): an argmax winner can sit on a narrow overfit
 * peak — a parameter set whose neighbors score much worse is a warning sign, not a validation, that
 * its own high score generalizes. {@link #plateauScore} evaluates a "one param at a time"
 * perturbation neighborhood around a candidate (numeric params: ±10%/±20%; int params: ±1 step;
 * enum/bool/string params: unperturbed, since there is no well-defined "nearby" value) and reports
 * the <em>median</em> fold-median Sharpe across that neighborhood — a candidate whose neighbors
 * score similarly has a real plateau; one whose neighbors collapse does not.
 *
 * <p>Strategy-agnostic by construction: neighbors are generated purely from each param's runtime
 * type in the existing {@code paramsSnapshot} map (no per-strategy-space code needed), then rebuilt
 * via {@link StrategyRegistry#create} — the same generic path {@code PromoteCli} already uses to
 * reconstruct a strategy from a persisted params map. An invalid perturbed combination (e.g. a
 * strategy constructor's own validation rejecting it) is skipped, not fatal — matches the existing
 * parameter-space convention of treating an invalid combo as "no candidate here", not an error (see
 * {@code FundingArbParameterSpace}).
 *
 * <p>{@code configOverride} is re-derived per neighbor from its own perturbed params map (see
 * {@link #configOverrideFor}) rather than reused from the original candidate, since {@link
 * CalibrationResult} does not retain the {@link Candidate} instance it came from — only its {@code
 * params()} snapshot. The one harness-level (not strategy-constructor) knob known to ride along in
 * that snapshot is {@code minHoldBars} ({@code FundingArbParameterSpace}); every other strategy's
 * params map has no such key.
 *
 * <p><strong>Findings from validating this task (PLAN-009 Task E.4 — full detail and evidence in
 * the Task J / 2026-07-14 verdict note):</strong>
 *
 * <ul>
 *   <li><strong>Fixed:</strong> percentage scaling ({@code d * factor}) is a no-op when a
 *       parameter's original value is exactly zero — and zero is a real, common threshold (e.g.
 *       fundingarb's {@code exitThreshold}: "exit when the funding rate crosses zero"), not a rare
 *       edge case. Fixed with a small absolute step (see {@link #ABSOLUTE_STEP_NEAR_ZERO}) for
 *       near-zero values.
 *   <li><strong>Known, not fixed (a real property of the parameter space, not a code bug):</strong>
 *       every strategy family calibrated by this project has one parameter — {@code
 *       FundingArbStrategy.confidenceScale}, {@code EmaCrossoverRsiStrategy.spreadScale} —
 *       confirmed (by reading both strategies' source) to feed only the <em>reported</em> {@code
 *       TechnicalSignal} confidence, never entry/exit timing. Perturbing it reproduces the original
 *       candidate's trades and Sharpe exactly, contributing 4 guaranteed-identical neighbors to
 *       every plateau computation (out of ~16–20 total). Combined with a median aggregation — where
 *       a large-enough identical-valued minority can still set the median — this makes {@code
 *       plateauScore == cvSharpeMedian} exactly considerably more often than a genuinely flat
 *       reward surface would justify on its own. Confirmed empirically: 15–16 of 20 top candidates
 *       showed this exact equality for both emarsi test pairs (BTCUSDT, ETHUSDT), and it dominated
 *       fundingarb's much smaller (2–8 candidate) survivor pools entirely. Not fixed here — a
 *       strategy-specific "skip this parameter" list would fix it but breaks the deliberate
 *       strategy-agnostic design for one field per strategy family; flagged as a real limitation
 *       for whoever next tunes plateau selection, not swept under the rug.
 * </ul>
 *
 * <p><strong>Decision (PLAN-011 Task H, finding F8, 2026-07-22):</strong> given the above, this
 * class's output is <strong>advisory-only</strong> — {@link app.viglide.research.cli.PromoteCli}'s
 * default {@code --select} was flipped from {@code plateau} to {@code argmax} (PLAN-009 Task E had
 * made {@code plateau} the default; E.4's own validation found it equalled {@code argmax} in all 4
 * tested combinations, so the flip changes no historical promotion). Chosen over adding a
 * strategy-declared "this param is confidence-only" exclusion hint: that would fix the
 * discrimination problem, but only by breaking the strategy-agnostic design this class deliberately
 * has for the sake of one field per strategy family — a worthwhile trade only once a strategy
 * family exists where the distinction actually matters. Revisit if one does (see {@code
 * ARCHITECTURE.md} §10's backlog entry).
 */
public final class PlateauScorer {

  private static final double[] PERCENT_PERTURBATIONS = {0.8, 0.9, 1.1, 1.2};

  /** Below this magnitude, a double param is treated as "zero" for perturbation purposes. */
  private static final double ZERO_EPSILON = 1e-9;

  /**
   * Absolute step used only for near-zero doubles, where percentage scaling is meaningless. Matches
   * the order of magnitude of this project's smallest real thresholds (fundingarb's entry/exit
   * thresholds run ~1e-5 to 1e-4) — a deliberate, documented choice, not a universal constant; a
   * strategy with legitimately smaller-scale zero-crossing thresholds would want a finer step, but
   * no current strategy family needs that.
   */
  private static final double ABSOLUTE_STEP_NEAR_ZERO = 1e-5;

  private PlateauScorer() {}

  /**
   * @param strategyName CLI short name (e.g. {@code "emarsi"}), used to rebuild each neighbor via
   *     {@link StrategyRegistry#create}
   * @param original the candidate to build a perturbation neighborhood around — only its {@code
   *     params()} snapshot is needed; {@code configOverride} is re-derived per neighbor (see {@link
   *     #configOverrideFor}), since {@link CalibrationResult} does not retain the original {@link
   *     Candidate} instance
   * @param chunks fold windows (with warm-up prefixes, PLAN-009 Task D) — identical to what the
   *     original candidate's own ranking was evaluated against
   * @return median of each valid neighbor's fold-median Sharpe; {@code original.cvSharpeMedian()}
   *     itself (the degenerate one-member "neighborhood") when no param could be perturbed at all
   *     or every perturbation was rejected as invalid
   */
  public static double plateauScore(
      String strategyName,
      CalibrationResult original,
      List<FoldSplitter.FoldWindow> chunks,
      String symbol,
      CandleInterval interval,
      BacktestConfig cfg,
      FoldRunner foldRunner) {
    List<Map<String, Object>> neighborParams = perturbations(original.params());
    StrategyRegistry registry = StrategyRegistry.load();

    List<Double> neighborSharpes = new ArrayList<>(neighborParams.size());
    for (Map<String, Object> params : neighborParams) {
      try {
        TradingStrategy strategy = registry.create(strategyName, CliArgs.camelMapToCliArgs(params));
        Candidate neighbor = new Candidate(strategy, params, configOverrideFor(params));
        CalibrationResult r =
            CalibrationHarness.evaluateAcrossFolds(
                neighbor, chunks, symbol, interval, cfg, foldRunner);
        neighborSharpes.add(r.cvSharpeMedian());
      } catch (IllegalArgumentException e) {
        // Invalid perturbed combination (e.g. a strategy constructor's own validation) — skip,
        // matching the existing parameter-space convention (FundingArbParameterSpace.build()).
      }
    }

    if (neighborSharpes.isEmpty()) {
      return original.cvSharpeMedian();
    }
    double[] xs = new double[neighborSharpes.size()];
    for (int i = 0; i < xs.length; i++) xs[i] = neighborSharpes.get(i);
    return median(xs);
  }

  /**
   * Re-derives the one harness-level (not strategy-constructor) knob known to ride along in a
   * params snapshot: {@code minHoldBars} (see {@code FundingArbParameterSpace}). Every other
   * strategy's params map has no such key, so this is the identity override for them — matches
   * {@link Candidate}'s own default.
   */
  private static UnaryOperator<BacktestConfig> configOverrideFor(Map<String, Object> params) {
    Object minHoldBars = params.get("minHoldBars");
    if (minHoldBars == null) {
      return UnaryOperator.identity();
    }
    int value = ((Number) minHoldBars).intValue();
    return c ->
        new BacktestConfig(
            c.startingCash(),
            c.fees(),
            c.warmupBars(),
            c.maxPositionPct(),
            c.stopLossPct(),
            c.takeProfitPct(),
            c.barsPerYear(),
            value);
  }

  /**
   * One-param-at-a-time perturbations of {@code params}: for every {@link Integer} entry, ±1 step;
   * for every {@link Double} entry, ×0.8/×0.9/×1.1/×1.2; every other type (String, Boolean,
   * enum-as-string, …) is left unperturbed and contributes no neighbor for that key, since there is
   * no well-defined "nearby" value for a categorical parameter.
   */
  static List<Map<String, Object>> perturbations(Map<String, Object> params) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map.Entry<String, Object> e : params.entrySet()) {
      String key = e.getKey();
      Object v = e.getValue();
      if (v instanceof Integer i) {
        out.add(withReplaced(params, key, i - 1));
        out.add(withReplaced(params, key, i + 1));
      } else if (v instanceof Double d) {
        if (Math.abs(d) < ZERO_EPSILON) {
          // Percentage scaling (d * factor) is a no-op at exactly zero -- and zero is a
          // legitimate, common threshold value (e.g. fundingarb's exitThreshold: "exit when the
          // funding rate crosses zero"), not a rare edge case to ignore. Fall back to a small
          // absolute step instead, so a zero-valued threshold still gets a real neighborhood.
          out.add(withReplaced(params, key, d - ABSOLUTE_STEP_NEAR_ZERO));
          out.add(withReplaced(params, key, d + ABSOLUTE_STEP_NEAR_ZERO));
        } else {
          for (double factor : PERCENT_PERTURBATIONS) {
            out.add(withReplaced(params, key, d * factor));
          }
        }
      }
    }
    return out;
  }

  private static Map<String, Object> withReplaced(
      Map<String, Object> params, String key, Object newValue) {
    Map<String, Object> copy = new LinkedHashMap<>(params);
    copy.put(key, newValue);
    return copy;
  }

  private static double median(double[] xs) {
    double[] sorted = xs.clone();
    Arrays.sort(sorted);
    int n = sorted.length;
    return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
  }
}
