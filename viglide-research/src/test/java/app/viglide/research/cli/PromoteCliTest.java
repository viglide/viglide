package app.viglide.research.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.viglide.core.params.JsonReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link PromoteCli} (PLAN-008 Task D.3/D.4): PSR/DSR computation, the {@code --min-dsr}
 * quality gate, and {@code oos*}-to-{@code cv*} read compatibility for calibration outputs written
 * before the rename.
 *
 * <p>Exercises {@link PromoteCli#run} directly (not {@code main}) — {@code main} calls {@link
 * System#exit}, which would kill the test JVM on the refusal path.
 */
class PromoteCliTest {

  private static final Path DATASET =
      Path.of("src/test/resources/fixtures/large_snippets/BTCUSDT_1h_month.csv").toAbsolutePath();

  @Test
  void smokeRun_promotedEntryHasPsrDsrTrialsAndCvFields(@TempDir Path tmp) throws Exception {
    Path calibDir = tmp.resolve("calib");
    Path winnersPath = tmp.resolve("winners.json");

    CalibrateCli.main(
        new String[] {
          "--strategy=emarsi",
          "--dataset=" + DATASET,
          "--symbol=BTCUSDT",
          "--interval=ONE_HOUR",
          "--warmup-bars=100",
          "--search=random",
          "--samples=20",
          "--folds=3",
          "--min-trades=0",
          "--out=" + calibDir,
          // PLAN-013 Task H: isolate the trial registry per test -- otherwise "trials" in the
          // manifest accumulates across every past run of this test suite (the registry is
          // deliberately append-only/persistent), breaking the trials==20 assertion below in a
          // way that depends on how many times this test has ever run, not this test's own logic.
          "--trial-registry=" + tmp.resolve("trials.jsonl")
        });

    int exitCode =
        PromoteCli.run(
            new String[] {
              "--from=" + calibDir,
              "--key=BTCUSDT_emarsi_smoke",
              "--winners=" + winnersPath,
              "--note=unit-test"
            });

    assertThat(exitCode).isEqualTo(0);
    assertThat(winnersPath).exists();

    @SuppressWarnings("unchecked")
    var winners = (java.util.Map<String, Object>) JsonReader.parse(Files.readString(winnersPath));
    @SuppressWarnings("unchecked")
    var entry = (java.util.Map<String, Object>) winners.get("BTCUSDT_emarsi_smoke");

    assertThat(entry).containsKeys("psr", "dsr", "trials", "cvSharpeMedian", "cvTotalReturnMedian");
    assertThat(((Number) entry.get("trials")).intValue()).isEqualTo(20);
    assertThat(((Number) entry.get("psr")).doubleValue()).isBetween(0.0, 1.0);
    assertThat(((Number) entry.get("dsr")).doubleValue()).isBetween(0.0, 1.0);
  }

  @Test
  void minDsrGate_refusesLowQualityPromotion_withoutMutatingWinners(@TempDir Path tmp)
      throws Exception {
    Path calibDir = tmp.resolve("calib");
    Path winnersPath = tmp.resolve("winners.json");

    CalibrateCli.main(
        new String[] {
          "--strategy=emarsi",
          "--dataset=" + DATASET,
          "--symbol=BTCUSDT",
          "--interval=ONE_HOUR",
          "--warmup-bars=100",
          "--search=random",
          "--samples=20",
          "--folds=3",
          "--min-trades=0",
          "--out=" + calibDir,
          // PLAN-013 Task H: isolate the trial registry per test -- otherwise "trials" in the
          // manifest accumulates across every past run of this test suite (the registry is
          // deliberately append-only/persistent), breaking the trials==20 assertion below in a
          // way that depends on how many times this test has ever run, not this test's own logic.
          "--trial-registry=" + tmp.resolve("trials.jsonl")
        });

    int exitCode =
        PromoteCli.run(
            new String[] {
              "--from=" + calibDir,
              "--key=BTCUSDT_emarsi_refused",
              "--winners=" + winnersPath,
              "--min-dsr=0.999999"
            });

    assertThat(exitCode).isEqualTo(1);
    // Refusal must not create (or corrupt) the winners file at all.
    assertThat(Files.exists(winnersPath)).isFalse();
  }

  // ── PLAN-010 Task A1: promotion floor (D10-2) ───────────────────────────────────────────────

  private static Path negativeCandidateCalibDir(Path tmp) throws IOException {
    Path calibDir = tmp.resolve("negative-calib");
    Files.createDirectories(calibDir);
    Files.writeString(
        calibDir.resolve("manifest.json"),
        """
        {
          "strategy": "emarsi",
          "args": {
            "dataset": "%s",
            "symbol": "BTCUSDT",
            "interval": "ONE_HOUR",
            "warmup-bars": "100"
          }
        }
        """
            .formatted(DATASET.toString().replace("\\", "\\\\")));
    Files.writeString(
        calibDir.resolve("top.json"),
        """
        [
          {
            "params": {"emaFast": 9, "emaSlow": 21, "rsiPeriod": 14,
                        "rsiOverbought": 70.0, "rsiOversold": 30.0, "spreadScale": 0.01},
            "cvSharpeMedian": -1.5,
            "cvTotalReturnMedian": -0.02,
            "cvMaxDrawdownWorst": 0.05,
            "cvTradeCountMedian": 12,
            "foldsEvaluated": 3
          }
        ]
        """);
    return calibDir;
  }

  @Test
  void negativeCandidate_refusedByDefault_evenOnANonDefaultWinnersPath(@TempDir Path tmp)
      throws Exception {
    Path calibDir = negativeCandidateCalibDir(tmp);
    Path winnersPath = tmp.resolve("winners.json"); // non-default location, but no --allow-negative

    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;
    int exitCode;
    try {
      System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
      exitCode =
          PromoteCli.run(
              new String[] {
                "--from=" + calibDir, "--key=BTCUSDT_emarsi_negative", "--winners=" + winnersPath
              });
    } finally {
      System.setErr(originalErr);
    }

    assertThat(exitCode).isEqualTo(1);
    assertThat(Files.exists(winnersPath)).isFalse();
    String stderr = captured.toString(StandardCharsets.UTF_8);
    assertThat(stderr).contains("REFUSED").contains("cvSharpeMedian=-1.5").contains("D10-2");
  }

  @Test
  void negativeCandidate_promotedOnlyWithBothAllowNegativeAndNonDefaultPath(@TempDir Path tmp)
      throws Exception {
    Path calibDir = negativeCandidateCalibDir(tmp);
    Path winnersPath = tmp.resolve("matrix_winners.json");

    int exitCode =
        PromoteCli.run(
            new String[] {
              "--from=" + calibDir,
              "--key=BTCUSDT_emarsi_negative",
              "--winners=" + winnersPath,
              "--allow-negative"
            });

    assertThat(exitCode).isEqualTo(0);
    assertThat(Files.exists(winnersPath)).isTrue();
    @SuppressWarnings("unchecked")
    var winners = (java.util.Map<String, Object>) JsonReader.parse(Files.readString(winnersPath));
    assertThat(winners).containsKey("BTCUSDT_emarsi_negative");
  }

  @Test
  void isDefaultRootWinnersPath_trueOnlyForTheLiteralDefaultRegardlessOfSpelling() {
    assertThat(PromoteCli.isDefaultRootWinnersPath(Path.of("winners.json"))).isTrue();
    assertThat(PromoteCli.isDefaultRootWinnersPath(Path.of("./winners.json"))).isTrue();
    assertThat(PromoteCli.isDefaultRootWinnersPath(Path.of("build/backtests/matrix_winners.json")))
        .isFalse();
    assertThat(PromoteCli.isDefaultRootWinnersPath(Path.of("winners-research.json"))).isFalse();
  }

  // ── PLAN-009 Task E: --select=plateau|argmax ────────────────────────────────────────────────

  private static Path threeCandidateCalibDir(Path tmp) throws IOException {
    Path calibDir = tmp.resolve("select-calib");
    Files.createDirectories(calibDir);
    Files.writeString(
        calibDir.resolve("manifest.json"),
        """
        {
          "strategy": "emarsi",
          "args": {
            "dataset": "%s",
            "symbol": "BTCUSDT",
            "interval": "ONE_HOUR",
            "warmup-bars": "100"
          }
        }
        """
            .formatted(DATASET.toString().replace("\\", "\\\\")));
    // A: best argmax (cvSharpeMedian=3.0) but weak plateau (0.5). B: best plateau (1.5) but only
    // 2nd-best argmax. C: worst on both -- never picked by either selector at --rank=1.
    Files.writeString(
        calibDir.resolve("top.json"),
        """
        [
          {"params": {"emaFast": 9, "emaSlow": 21, "rsiPeriod": 14, "rsiOverbought": 70.0,
                       "rsiOversold": 30.0, "spreadScale": 0.01},
           "cvSharpeMedian": 3.0, "cvTotalReturnMedian": 0.03, "cvMaxDrawdownWorst": 0.02,
           "cvTradeCountMedian": 15, "foldsEvaluated": 3, "plateauScore": 0.5},
          {"params": {"emaFast": 10, "emaSlow": 22, "rsiPeriod": 14, "rsiOverbought": 70.0,
                       "rsiOversold": 30.0, "spreadScale": 0.01},
           "cvSharpeMedian": 2.0, "cvTotalReturnMedian": 0.02, "cvMaxDrawdownWorst": 0.02,
           "cvTradeCountMedian": 15, "foldsEvaluated": 3, "plateauScore": 1.5},
          {"params": {"emaFast": 11, "emaSlow": 23, "rsiPeriod": 14, "rsiOverbought": 70.0,
                       "rsiOversold": 30.0, "spreadScale": 0.01},
           "cvSharpeMedian": 1.0, "cvTotalReturnMedian": 0.01, "cvMaxDrawdownWorst": 0.02,
           "cvTradeCountMedian": 15, "foldsEvaluated": 3, "plateauScore": 1.0}
        ]
        """);
    return calibDir;
  }

  @Test
  void selectArgmax_isTheDefault_picksHighestCvSharpeMedian_ignoringPlateauScore(@TempDir Path tmp)
      throws Exception {
    Path calibDir = threeCandidateCalibDir(tmp);
    Path winnersPath = tmp.resolve("winners.json");

    // No --select at all -- argmax is the documented default (PLAN-011 Task H / F8: plateau
    // selection is advisory-only, not proven to discriminate for any current strategy family).
    int exitCode =
        PromoteCli.run(
            new String[] {
              "--from=" + calibDir, "--key=BTCUSDT_emarsi_argmax", "--winners=" + winnersPath
            });

    assertThat(exitCode).isEqualTo(0);
    @SuppressWarnings("unchecked")
    var winners = (java.util.Map<String, Object>) JsonReader.parse(Files.readString(winnersPath));
    @SuppressWarnings("unchecked")
    var entry = (java.util.Map<String, Object>) winners.get("BTCUSDT_emarsi_argmax");
    assertThat(((Number) entry.get("cvSharpeMedian")).doubleValue()).isEqualTo(3.0);
  }

  @Test
  void selectPlateau_explicitlyRequested_picksHighestPlateauScoreNotHighestCvSharpeMedian(
      @TempDir Path tmp) throws Exception {
    Path calibDir = threeCandidateCalibDir(tmp);
    Path winnersPath = tmp.resolve("winners.json");

    int exitCode =
        PromoteCli.run(
            new String[] {
              "--from=" + calibDir,
              "--key=BTCUSDT_emarsi_plateau",
              "--winners=" + winnersPath,
              "--select=plateau"
            });

    assertThat(exitCode).isEqualTo(0);
    @SuppressWarnings("unchecked")
    var winners = (java.util.Map<String, Object>) JsonReader.parse(Files.readString(winnersPath));
    @SuppressWarnings("unchecked")
    var entry = (java.util.Map<String, Object>) winners.get("BTCUSDT_emarsi_plateau");
    // Candidate B (cvSharpeMedian=2.0) has the highest plateauScore (1.5), not candidate A
    // (cvSharpeMedian=3.0, the argmax winner) -- proves plateau selection actually changed which
    // candidate got promoted, not just that it ran without error.
    assertThat(((Number) entry.get("cvSharpeMedian")).doubleValue()).isEqualTo(2.0);
  }

  @Test
  void selectRejectsUnknownValue(@TempDir Path tmp) throws Exception {
    Path calibDir = threeCandidateCalibDir(tmp);
    assertThatThrownBy(
            () ->
                PromoteCli.run(
                    new String[] {
                      "--from=" + calibDir,
                      "--key=x",
                      "--winners=" + tmp.resolve("winners.json"),
                      "--select=bogus"
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown --select");
  }

  // ── oos* legacy-key compatibility (PLAN-008 Task D.2) ───────────────────────────────────────

  @Test
  void legacyOosSpelledCalibrationOutput_stillPromotesSuccessfully(@TempDir Path tmp)
      throws IOException {
    Path calibDir = tmp.resolve("legacy-calib");
    Files.createDirectories(calibDir);
    Path winnersPath = tmp.resolve("winners.json");

    // Simulates a calibration output dir written by a pre-rename CalibrateCli: top.json entries
    // use oos* keys (no cv* at all), and manifest.json predates trial accounting (D.1) entirely.
    Files.writeString(
        calibDir.resolve("manifest.json"),
        """
        {
          "strategy": "emarsi",
          "args": {
            "dataset": "%s",
            "symbol": "BTCUSDT",
            "interval": "ONE_HOUR",
            "warmup-bars": "100"
          }
        }
        """
            .formatted(DATASET.toString().replace("\\", "\\\\")));
    Files.writeString(
        calibDir.resolve("top.json"),
        """
        [
          {
            "params": {"emaFast": 9, "emaSlow": 21, "rsiPeriod": 14,
                        "rsiOverbought": 70.0, "rsiOversold": 30.0, "spreadScale": 0.01},
            "oosSharpeMedian": 0.5,
            "oosTotalReturnMedian": 0.01,
            "oosMaxDrawdownWorst": 0.02,
            "oosTradeCountMedian": 15,
            "foldsEvaluated": 3
          }
        ]
        """);

    int exitCode =
        PromoteCli.run(
            new String[] {
              "--from=" + calibDir, "--key=BTCUSDT_emarsi_legacy", "--winners=" + winnersPath
            });

    assertThat(exitCode).isEqualTo(0);
    @SuppressWarnings("unchecked")
    var winners = (java.util.Map<String, Object>) JsonReader.parse(Files.readString(winnersPath));
    @SuppressWarnings("unchecked")
    var entry = (java.util.Map<String, Object>) winners.get("BTCUSDT_emarsi_legacy");

    // Read via the oos* fallback, but re-written under the new cv* spelling going forward.
    assertThat(((Number) entry.get("cvSharpeMedian")).doubleValue()).isEqualTo(0.5);
    assertThat(((Number) entry.get("trials")).intValue()).isEqualTo(0); // manifest had none
    assertThat(entry).containsKeys("psr", "dsr");
  }
}
