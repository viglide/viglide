package app.viglide.core.params;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * All fixtures here are synthetic (invented symbols/thresholds), never values copied from the real
 * local {@code winners.json} — CLAUDE.md §8 treats tuned parameters as secrets even in tests.
 */
class WinnersParamsLoaderTest {

  private static final String FIXTURE =
      """
      {
        "ETHUSDT_fundingarb_2023": {
          "params": { "windowSize": 3, "entryThreshold": 0.0002, "confidenceScale": 0.001 },
          "cvSharpeMedian": 2.1
        },
        "ETHUSDT_fundingarb_2024": {
          "params": { "windowSize": 2, "entryThreshold": 0.00015, "confidenceScale": 0.0012 },
          "cvSharpeMedian": 3.4
        },
        "BTCUSDT_emarsi_2023": {
          "params": { "emaFast": 12, "emaSlow": 26 },
          "cvSharpeMedian": 0.9
        },
        "malformedNoParams_x_2023": {
          "cvSharpeMedian": 0.1
        }
      }
      """;

  @Test
  void resolve_picksTheMostRecentYear_forTheMatchingStrategyAndSymbol(@TempDir Path tmp)
      throws Exception {
    Path winnersPath = tmp.resolve("winners.json");
    Files.writeString(winnersPath, FIXTURE);

    WinnersParamsLoader.Resolved resolved =
        WinnersParamsLoader.resolve(winnersPath, "fundingarb", "ETHUSDT");

    assertThat(resolved.trainingYear()).isEqualTo(2024); // not 2023 -- the older entry
    assertThat(resolved.args()).containsEntry("window-size", "2");
    assertThat(resolved.args()).containsEntry("entry-threshold", "0.00015");
    assertThat(resolved.paramsHash()).hasSize(12);
  }

  @Test
  void resolve_camelCaseParamsBecomeKebabCaseCliArgs(@TempDir Path tmp) throws Exception {
    Path winnersPath = tmp.resolve("winners.json");
    Files.writeString(winnersPath, FIXTURE);

    WinnersParamsLoader.Resolved resolved =
        WinnersParamsLoader.resolve(winnersPath, "emarsi", "BTCUSDT");

    assertThat(resolved.args()).containsEntry("ema-fast", "12").containsEntry("ema-slow", "26");
    assertThat(resolved.trainingYear()).isEqualTo(2023);
  }

  @Test
  void resolve_noMatchingEntry_failsLoudly_listingWhatWasFound(@TempDir Path tmp) throws Exception {
    Path winnersPath = tmp.resolve("winners.json");
    Files.writeString(winnersPath, FIXTURE);

    assertThatThrownBy(() -> WinnersParamsLoader.resolve(winnersPath, "fundingarb", "SOLUSDT"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fundingarb")
        .hasMessageContaining("SOLUSDT")
        // fail-loud contract: the error must surface what keys *were* found, not just "not found"
        .hasMessageContaining("ETHUSDT_fundingarb_2023")
        .hasMessageContaining("ETHUSDT_fundingarb_2024");
  }

  @Test
  void resolve_missingFile_failsLoudly(@TempDir Path tmp) {
    Path missing = tmp.resolve("does-not-exist.json");

    assertThatThrownBy(() -> WinnersParamsLoader.resolve(missing, "fundingarb", "ETHUSDT"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not exist");
  }

  @Test
  void resolve_entryWithoutParamsObject_failsLoudly(@TempDir Path tmp) throws Exception {
    Path winnersPath = tmp.resolve("winners.json");
    Files.writeString(winnersPath, FIXTURE);

    assertThatThrownBy(() -> WinnersParamsLoader.resolve(winnersPath, "x", "malformedNoParams"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("'params'");
  }

  @Test
  void resolve_neverExposesRawValuesInTheHash(@TempDir Path tmp) throws Exception {
    Path winnersPath = tmp.resolve("winners.json");
    Files.writeString(winnersPath, FIXTURE);

    WinnersParamsLoader.Resolved resolved =
        WinnersParamsLoader.resolve(winnersPath, "fundingarb", "ETHUSDT");

    assertThat(resolved.paramsHash()).doesNotContain("0.00015").matches("[0-9a-f]{12}");
  }
}
