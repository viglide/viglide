package app.viglide.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * PLAN-009 Task B1: every interval's wall-clock duration, the single source of truth for any
 * interval-dependent arithmetic elsewhere in the codebase.
 */
class CandleIntervalTest {

  @Test
  void duration_matchesEachIntervalsWallClockLength() {
    assertThat(CandleInterval.ONE_MINUTE.duration()).isEqualTo(Duration.ofMinutes(1));
    assertThat(CandleInterval.FIVE_MINUTES.duration()).isEqualTo(Duration.ofMinutes(5));
    assertThat(CandleInterval.FIFTEEN_MINUTES.duration()).isEqualTo(Duration.ofMinutes(15));
    assertThat(CandleInterval.ONE_HOUR.duration()).isEqualTo(Duration.ofHours(1));
    assertThat(CandleInterval.FOUR_HOURS.duration()).isEqualTo(Duration.ofHours(4));
    assertThat(CandleInterval.ONE_DAY.duration()).isEqualTo(Duration.ofDays(1));
  }

  @Test
  void duration_isStrictlyIncreasingInDeclarationOrder() {
    CandleInterval[] ascending = CandleInterval.values();
    for (int i = 1; i < ascending.length; i++) {
      assertThat(ascending[i].duration()).isGreaterThan(ascending[i - 1].duration());
    }
  }

  @Test
  void valueOf_parsesEveryDeclaredConstantName() {
    for (CandleInterval interval : CandleInterval.values()) {
      assertThat(CandleInterval.valueOf(interval.name())).isSameAs(interval);
    }
  }
}
