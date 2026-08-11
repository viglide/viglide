package app.viglide.research.cohort;

/**
 * The two cohorts PLAN-024 §0 C6 / ADR-0028 S5 name explicitly: {@code MAJOR} (BTC/ETH-style
 * large-caps) versus everything else. Two cohorts, not more — ADR-0028: "cohorts multiply the
 * search space and the gate must be paid for accordingly. Two cohorts is two hypotheses against
 * PLAN-024's budget of three, not one."
 */
public enum Cohort {
  MAJOR,
  ALT
}
