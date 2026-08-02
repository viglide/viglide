package app.viglide.core.params;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CliArgs}. */
class CliArgsTest {

  @Test
  void parsesKeyValuePairs() {
    Map<String, String> m = CliArgs.parse(new String[] {"--strategy=emarsi", "--samples=100"});
    assertThat(m).containsEntry("strategy", "emarsi").containsEntry("samples", "100");
  }

  @Test
  void parsesBareFlagsAsTrue() {
    Map<String, String> m = CliArgs.parse(new String[] {"--verbose"});
    assertThat(m).containsEntry("verbose", "true");
  }

  @Test
  void rejectsPositionalArgs() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CliArgs.parse(new String[] {"emarsi"}))
        .withMessageContaining("positional args");
  }

  @Test
  void requireRejectsMissingKey() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CliArgs.require(Map.of(), "dataset"))
        .withMessageContaining("dataset");
  }

  @Test
  void typedOpts_fallBackWhenAbsent() {
    Map<String, String> empty = Map.of();
    assertThat(CliArgs.intOpt(empty, "k", 42)).isEqualTo(42);
    assertThat(CliArgs.doubleOpt(empty, "k", 1.5)).isEqualTo(1.5);
    assertThat(CliArgs.bigDecOpt(empty, "k", new BigDecimal("3.14"))).isEqualByComparingTo("3.14");
    assertThat(CliArgs.opt(empty, "k", "fallback")).isEqualTo("fallback");
  }

  @Test
  void typedOpts_parseWhenPresent() {
    Map<String, String> m = Map.of("n", "7", "x", "2.5", "y", "0.0001", "s", "value");
    assertThat(CliArgs.intOpt(m, "n", -1)).isEqualTo(7);
    assertThat(CliArgs.doubleOpt(m, "x", -1)).isEqualTo(2.5);
    assertThat(CliArgs.bigDecOpt(m, "y", BigDecimal.ZERO)).isEqualByComparingTo("0.0001");
    assertThat(CliArgs.opt(m, "s", "fallback")).isEqualTo("value");
  }

  @Test
  void camelMapToCliArgs_convertsCamelCaseKeysToKebabCase() {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("emaFast", 12);
    params.put("rsiOverbought", 65.0);
    params.put("windowSize", null);

    Map<String, String> args = CliArgs.camelMapToCliArgs(params);

    assertThat(args)
        .containsEntry("ema-fast", "12")
        .containsEntry("rsi-overbought", "65.0")
        .containsEntry("window-size", "");
  }
}
