package app.viglide.core.params;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsonReader} — round trips through {@link JsonWriter} and confirms shape
 * fidelity for the value types we actually emit.
 */
class JsonReaderTest {

  @Test
  void roundTripsObject() {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("name", "viglide");
    m.put("n", 42);
    m.put("ratio", new BigDecimal("0.0001"));
    m.put("ok", Boolean.TRUE);
    String json = JsonWriter.pretty(m);
    Object back = JsonReader.parse(json);
    assertThat(back).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = (Map<String, Object>) back;
    assertThat(parsed.get("name")).isEqualTo("viglide");
    assertThat(((BigDecimal) parsed.get("n")).intValue()).isEqualTo(42);
    assertThat((BigDecimal) parsed.get("ratio")).isEqualByComparingTo("0.0001");
    assertThat(parsed.get("ok")).isEqualTo(Boolean.TRUE);
  }

  @Test
  void parsesNestedArrays() {
    String json = "{\"items\": [1, 2, [3, 4], {\"k\": \"v\"}], \"empty\": []}";
    @SuppressWarnings("unchecked")
    Map<String, Object> m = (Map<String, Object>) JsonReader.parse(json);
    @SuppressWarnings("unchecked")
    List<Object> items = (List<Object>) m.get("items");
    assertThat(items).hasSize(4);
    assertThat((List<?>) items.get(2)).hasSize(2);
    @SuppressWarnings("unchecked")
    Map<String, Object> nested = (Map<String, Object>) items.get(3);
    assertThat(nested).containsEntry("k", "v");
  }

  @Test
  void handlesEscapesAndNulls() {
    String json = "{\"s\": \"line1\\nline2\\\"q\\\"\", \"x\": null}";
    @SuppressWarnings("unchecked")
    Map<String, Object> m = (Map<String, Object>) JsonReader.parse(json);
    assertThat(m.get("s")).isEqualTo("line1\nline2\"q\"");
    assertThat(m.containsKey("x")).isTrue();
    assertThat(m.get("x")).isNull();
  }
}
