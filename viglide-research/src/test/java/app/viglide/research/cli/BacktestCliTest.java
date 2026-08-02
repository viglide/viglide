package app.viglide.research.cli;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PLAN-010 Task A4 (consumer audit): {@link BacktestCli}'s {@code --ensemble-from-winners} path
 * must fail loudly on a missing {@code winners.json} entry, never silently default or skip it.
 * {@code buildEnsemble} runs before the kline dataset is ever read, so these tests don't need a
 * real dataset file — the expected exception fires first.
 */
class BacktestCliTest {

  @Test
  void ensembleFromWinners_missingEntry_throwsRatherThanSilentlyDefaulting(@TempDir Path tmp)
      throws IOException {
    Path winnersPath = tmp.resolve("winners.json");
    Files.writeString(winnersPath, "{}"); // no entries at all

    assertThatThrownBy(
            () ->
                BacktestCli.main(
                    new String[] {
                      "--strategy=ensemble",
                      "--strategies=emarsi",
                      "--ensemble-from-winners=BTCUSDT_<strategy>_2025",
                      "--winners=" + winnersPath,
                      "--dataset=" + tmp.resolve("does-not-exist.csv"),
                      "--symbol=BTCUSDT",
                      "--interval=ONE_HOUR"
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no winners.json entry for")
        .hasMessageContaining("BTCUSDT_emarsi_2025");
  }

  @Test
  void sharpeWeightedCombiner_missingEntry_throwsRatherThanSilentlyDefaultingToZero(
      @TempDir Path tmp) throws IOException {
    Path winnersPath = tmp.resolve("winners.json");
    // Entry exists for the ensemble-build step, but has no cvSharpeMedian/oosSharpeMedian --
    // the sharpe-weighted combiner path must still fail loudly, not silently treat it as 0.
    Files.writeString(
        winnersPath,
        """
        {
          "BTCUSDT_emarsi_2025": {
            "params": {"emaFast": 9, "emaSlow": 21, "rsiPeriod": 14,
                        "rsiOverbought": 70.0, "rsiOversold": 30.0, "spreadScale": 0.01}
          }
        }
        """);

    assertThatThrownBy(
            () ->
                BacktestCli.main(
                    new String[] {
                      "--strategy=ensemble",
                      "--strategies=emarsi",
                      "--combiner=sharpe-weighted",
                      "--ensemble-from-winners=BTCUSDT_<strategy>_2025",
                      "--winners=" + winnersPath,
                      "--dataset=" + tmp.resolve("does-not-exist.csv"),
                      "--symbol=BTCUSDT",
                      "--interval=ONE_HOUR"
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cvSharpeMedian");
  }
}
