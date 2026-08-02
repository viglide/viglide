package app.viglide.core.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Record-invariant tests for {@link RiskParameters}. */
class RiskParametersTest {

  private static final Duration STALE_AGE = Duration.ofMinutes(90);

  @Test
  void defaults_matchClaudeHardLimits() {
    RiskParameters p = RiskParameters.defaults();
    assertThat(p.maxPositionPct()).isEqualByComparingTo("0.02");
    assertThat(p.maxPortfolioDrawdownPct()).isEqualByComparingTo("0.15");
    assertThat(p.maxLeverage()).isEqualByComparingTo("2.0");
    assertThat(p.maxDailyVolumePct()).isEqualByComparingTo("0.005");
    assertThat(p.maxPortfolioRiskPct()).isEqualByComparingTo("0.005");
    assertThat(p.stopLossAtrMult()).isEqualTo(2.0);
    assertThat(p.confidenceFloor()).isEqualTo(0.5);
    assertThat(p.maxStaleInputAge()).isEqualTo(Duration.ofMinutes(90));
    // PLAN-012 Task F: absolute limits disabled by default -- every existing backtest/test using
    // defaults() is unaffected by this task's existence.
    assertThat(p.maxTotalDeployedAbs()).isEmpty();
    assertThat(p.maxPositionAbs()).isEmpty();
    assertThat(p.maxDailyLossAbs()).isEmpty();
    assertThat(p.maxCampaignLossAbs()).isEmpty();
  }

  @Test
  void rejectsNullFields() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new RiskParameters(
                    null,
                    new BigDecimal("0.15"),
                    new BigDecimal("2"),
                    new BigDecimal("0.005"),
                    new BigDecimal("0.005"),
                    2.0,
                    0.5,
                    STALE_AGE,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));
  }

  @Test
  void rejectsZeroOrNegativeMaxPositionPct() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new RiskParameters(
                    BigDecimal.ZERO,
                    new BigDecimal("0.15"),
                    new BigDecimal("2"),
                    new BigDecimal("0.005"),
                    new BigDecimal("0.005"),
                    2.0,
                    0.5,
                    STALE_AGE,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));
  }

  @Test
  void rejectsMaxPositionPctAboveOne() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new RiskParameters(
                    new BigDecimal("1.01"),
                    new BigDecimal("0.15"),
                    new BigDecimal("2"),
                    new BigDecimal("0.005"),
                    new BigDecimal("0.005"),
                    2.0,
                    0.5,
                    STALE_AGE,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));
  }

  @Test
  void rejectsZeroStopLossAtrMult() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new RiskParameters(
                    new BigDecimal("0.02"),
                    new BigDecimal("0.15"),
                    new BigDecimal("2"),
                    new BigDecimal("0.005"),
                    new BigDecimal("0.005"),
                    0.0,
                    0.5,
                    STALE_AGE,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));
  }

  @Test
  void rejectsConfidenceFloorOutOfRange() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new RiskParameters(
                    new BigDecimal("0.02"),
                    new BigDecimal("0.15"),
                    new BigDecimal("2"),
                    new BigDecimal("0.005"),
                    new BigDecimal("0.005"),
                    2.0,
                    1.1,
                    STALE_AGE,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));
  }

  @Test
  void rejectsZeroOrNegativeMaxStaleInputAge() {
    // F7: staleness tolerance must itself be a positive duration.
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new RiskParameters(
                    new BigDecimal("0.02"),
                    new BigDecimal("0.15"),
                    new BigDecimal("2"),
                    new BigDecimal("0.005"),
                    new BigDecimal("0.005"),
                    2.0,
                    0.5,
                    Duration.ZERO,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new RiskParameters(
                    new BigDecimal("0.02"),
                    new BigDecimal("0.15"),
                    new BigDecimal("2"),
                    new BigDecimal("0.005"),
                    new BigDecimal("0.005"),
                    2.0,
                    0.5,
                    Duration.ofMinutes(-1),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));
  }

  @Test
  void acceptsLeverageAboveOne() {
    // maxLeverage > 1 is normal (2× leverage is the default).
    var p =
        new RiskParameters(
            new BigDecimal("0.02"),
            new BigDecimal("0.15"),
            new BigDecimal("3"),
            new BigDecimal("0.005"),
            new BigDecimal("0.005"),
            2.0,
            0.5,
            STALE_AGE,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    assertThat(p.maxLeverage()).isEqualByComparingTo("3");
  }

  // ── PLAN-012 Task F: absolute-dollar limits ─────────────────────────────────────────────────

  @Test
  void defaultsWithAbsoluteLimits_populatesOnlyTheFourNewFields() {
    RiskParameters p =
        RiskParameters.defaultsWithAbsoluteLimits(
            Optional.of(new BigDecimal("500")),
            Optional.of(new BigDecimal("150")),
            Optional.of(new BigDecimal("50")),
            Optional.of(new BigDecimal("150")));

    assertThat(p.maxTotalDeployedAbs()).contains(new BigDecimal("500"));
    assertThat(p.maxPositionAbs()).contains(new BigDecimal("150"));
    assertThat(p.maxDailyLossAbs()).contains(new BigDecimal("50"));
    assertThat(p.maxCampaignLossAbs()).contains(new BigDecimal("150"));
    // Every other field is untouched from defaults().
    assertThat(p.maxPositionPct()).isEqualByComparingTo(RiskParameters.defaults().maxPositionPct());
    assertThat(p.maxLeverage()).isEqualByComparingTo(RiskParameters.defaults().maxLeverage());
  }

  @Test
  void rejectsZeroOrNegativeAbsoluteLimitsWhenPresent() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                RiskParameters.defaultsWithAbsoluteLimits(
                    Optional.of(BigDecimal.ZERO),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                RiskParameters.defaultsWithAbsoluteLimits(
                    Optional.empty(),
                    Optional.of(new BigDecimal("-1")),
                    Optional.empty(),
                    Optional.empty()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                RiskParameters.defaultsWithAbsoluteLimits(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(BigDecimal.ZERO),
                    Optional.empty()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                RiskParameters.defaultsWithAbsoluteLimits(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(BigDecimal.ZERO)));
  }

  @Test
  void rejectsMaxPositionAbsGreaterThanMaxTotalDeployedAbs() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                RiskParameters.defaultsWithAbsoluteLimits(
                    Optional.of(new BigDecimal("100")),
                    Optional.of(new BigDecimal("100.01")), // > total -- nonsensical
                    Optional.empty(),
                    Optional.empty()))
        .withMessageContaining("maxPositionAbs");
  }

  @Test
  void acceptsMaxPositionAbsEqualToMaxTotalDeployedAbs() {
    // Boundary case: a single position consuming the entire total cap is legal (<=, not <).
    RiskParameters p =
        RiskParameters.defaultsWithAbsoluteLimits(
            Optional.of(new BigDecimal("100")),
            Optional.of(new BigDecimal("100")),
            Optional.empty(),
            Optional.empty());
    assertThat(p.maxPositionAbs()).contains(new BigDecimal("100"));
  }
}
