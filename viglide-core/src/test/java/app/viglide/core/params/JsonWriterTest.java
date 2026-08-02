package app.viglide.core.params;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link JsonWriter} — output shape, escaping, and number formatting. */
class JsonWriterTest {

  @Test
  void emitsCompactObject() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", "viglide");
    m.put("n", 42);
    assertThat(JsonWriter.compact(m)).isEqualTo("{\"name\":\"viglide\",\"n\":42}");
  }

  @Test
  void prettyOutputIndentsTwoSpaces() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("a", 1);
    m.put("b", List.of(2, 3));
    String s = JsonWriter.pretty(m);
    assertThat(s).contains("\n  \"a\": 1").contains("\n  \"b\": [").contains("\n    2");
  }

  @Test
  void bigDecimalUsesPlainDecimal() {
    assertThat(JsonWriter.compact(Map.of("v", new BigDecimal("0.00010000"))))
        .contains("0.00010000")
        .doesNotContain("E");
  }

  @Test
  void escapesControlCharactersAndQuotes() {
    String s = JsonWriter.compact(Map.of("msg", "line1\nline2\t\"quoted\""));
    assertThat(s).contains("\\n").contains("\\t").contains("\\\"quoted\\\"");
  }

  @Test
  void emptyContainersHaveCompactForm() {
    assertThat(JsonWriter.pretty(Map.of())).isEqualTo("{}");
    assertThat(JsonWriter.pretty(List.of())).isEqualTo("[]");
  }

  @Test
  void rejectsUnsupportedValueTypes() {
    assertThatThrownBy(() -> JsonWriter.compact(Map.of("x", new Object())))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
