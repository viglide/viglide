package app.viglide.core.params;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ParamsHashTest {

  @Test
  void sameEntries_differentInsertionOrder_produceTheSameHash() {
    Map<String, String> a = new LinkedHashMap<>();
    a.put("windowSize", "3");
    a.put("entryThreshold", "0.0002");

    Map<String, String> b = new LinkedHashMap<>();
    b.put("entryThreshold", "0.0002");
    b.put("windowSize", "3");

    assertThat(ParamsHash.of(a)).isEqualTo(ParamsHash.of(b));
  }

  @Test
  void differentValues_produceDifferentHashes() {
    Map<String, String> a = Map.of("windowSize", "3");
    Map<String, String> b = Map.of("windowSize", "4");

    assertThat(ParamsHash.of(a)).isNotEqualTo(ParamsHash.of(b));
  }

  @Test
  void hash_neverContainsTheRawValues() {
    Map<String, String> secret =
        new TreeMap<>(Map.of("entryThreshold", "0.00012345", "windowSize", "6"));

    String hash = ParamsHash.of(secret);

    assertThat(hash).doesNotContain("0.00012345").doesNotContain("entryThreshold");
  }

  @Test
  void hash_isATwelveCharacterHexString() {
    String hash = ParamsHash.of(Map.of("k", "v"));

    assertThat(hash).hasSize(12).matches("[0-9a-f]{12}");
  }
}
