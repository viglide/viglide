package app.viglide.research.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for the long-form CLI argument parser. */
class ArgsTest {

  @Test
  void parsesKeyValuePairs() {
    Map<String, String> m = Args.parse(new String[] {"--strategy=emarsi", "--samples=100"});
    assertThat(m).containsEntry("strategy", "emarsi").containsEntry("samples", "100");
  }

  @Test
  void parsesBareFlagsAsTrue() {
    Map<String, String> m = Args.parse(new String[] {"--verbose"});
    assertThat(Args.flag(m, "verbose")).isTrue();
  }

  @Test
  void rejectsPositionalArgs() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Args.parse(new String[] {"emarsi"}))
        .withMessageContaining("positional args");
  }

  @Test
  void requireRejectsMissingKey() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Args.require(Map.of(), "dataset"))
        .withMessageContaining("dataset");
  }

  @Test
  void typedOpts_fallBackWhenAbsent() {
    Map<String, String> empty = Map.of();
    assertThat(Args.intOpt(empty, "k", 42)).isEqualTo(42);
    assertThat(Args.doubleOpt(empty, "k", 1.5)).isEqualTo(1.5);
    assertThat(Args.bigDecOpt(empty, "k", new BigDecimal("3.14"))).isEqualByComparingTo("3.14");
  }

  @Test
  void typedOpts_parseWhenPresent() {
    Map<String, String> m = Map.of("n", "7", "x", "2.5", "y", "0.0001");
    assertThat(Args.intOpt(m, "n", -1)).isEqualTo(7);
    assertThat(Args.doubleOpt(m, "x", -1)).isEqualTo(2.5);
    assertThat(Args.bigDecOpt(m, "y", BigDecimal.ZERO)).isEqualByComparingTo("0.0001");
  }

  // ── jsonFallback (PLAN-008 Task D.2) ────────────────────────────────────────────────────────

  @Test
  void jsonFallback_prefersPreferredKeyWhenBothPresent() {
    Map<String, Object> obj = Map.of("cvSharpeMedian", 1.5, "oosSharpeMedian", 9.9);
    assertThat(Args.jsonFallback(obj, "cvSharpeMedian", "oosSharpeMedian")).isEqualTo(1.5);
  }

  @Test
  void jsonFallback_fallsBackToLegacyKeyWhenPreferredAbsent() {
    Map<String, Object> obj = Map.of("oosSharpeMedian", 9.9);
    assertThat(Args.jsonFallback(obj, "cvSharpeMedian", "oosSharpeMedian")).isEqualTo(9.9);
  }

  @Test
  void jsonFallback_isNullWhenNeitherKeyPresent() {
    assertThat(Args.jsonFallback(Map.of(), "cvSharpeMedian", "oosSharpeMedian")).isNull();
  }
}
