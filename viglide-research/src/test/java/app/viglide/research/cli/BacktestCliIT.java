package app.viglide.research.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test: runs {@link BacktestCli#main} end-to-end against the committed BTC 1h month
 * snippet and asserts that {@code result.json}, {@code trades.csv}, and {@code equity.csv} are
 * written.
 */
class BacktestCliIT {

  @Test
  void emarsiOnHourlyMonthSnippet_producesWellFormedOutputs(@TempDir Path tmp) throws IOException {
    Path dataset =
        Path.of("src/test/resources/fixtures/large_snippets/BTCUSDT_1h_month.csv").toAbsolutePath();
    assertThat(dataset).exists();

    Path outDir = tmp.resolve("bt");
    BacktestCli.main(
        new String[] {
          "--strategy=emarsi",
          "--dataset=" + dataset,
          "--symbol=BTCUSDT",
          "--interval=ONE_HOUR",
          "--warmup-bars=100", // smaller warmup so the month snippet (~720 bars) has runway
          "--out=" + outDir
        });

    assertThat(outDir.resolve("result.json")).exists();
    assertThat(outDir.resolve("trades.csv")).exists();
    assertThat(outDir.resolve("equity.csv")).exists();

    String result = Files.readString(outDir.resolve("result.json"));
    assertThat(result)
        .contains("\"strategy\": \"emarsi\"")
        .contains("\"symbol\": \"BTCUSDT\"")
        .contains("\"interval\": \"ONE_HOUR\"")
        .contains("\"sharpe\"")
        .contains("\"maxDrawdown\"")
        .contains("\"tradeCount\"");

    String equity = Files.readString(outDir.resolve("equity.csv"));
    assertThat(equity).startsWith("timestamp,equity\n");
    // 720 candles minus the 100-bar warmup = 620 mark-to-market points… approximately.
    long lines = equity.lines().count();
    assertThat(lines).isGreaterThan(500L);
  }

  // ── F6: refusal-aware audit trail ───────────────────────────────────────────────────────────

  @Test
  void extremeConfidenceFloor_producesNonEmptyRefusalsCsv(@TempDir Path tmp) throws IOException {
    Path dataset =
        Path.of("src/test/resources/fixtures/large_snippets/BTCUSDT_1h_month.csv").toAbsolutePath();
    Path outDir = tmp.resolve("bt");

    // 0.99 confidence floor: emarsi's signals on this snippet are all well below that, so the RM
    // (on by default) refuses every non-HOLD signal with CONFIDENCE_BELOW_THRESHOLD.
    BacktestCli.main(
        new String[] {
          "--strategy=emarsi",
          "--dataset=" + dataset,
          "--symbol=BTCUSDT",
          "--interval=ONE_HOUR",
          "--warmup-bars=100",
          "--rm-confidence-floor=0.99",
          "--out=" + outDir
        });

    Path refusalsCsv = outDir.resolve("refusals.csv");
    assertThat(refusalsCsv).exists();
    List<String> lines = Files.readAllLines(refusalsCsv);
    assertThat(lines.get(0)).isEqualTo("time,symbol,side,reason,explanation");
    assertThat(lines).as("header + at least one refusal row").hasSizeGreaterThan(1);
    assertThat(lines.get(1)).contains("CONFIDENCE_BELOW_THRESHOLD");

    String result = Files.readString(outDir.resolve("result.json"));
    assertThat(result).doesNotContain("\"refusals\": 0");
  }

  // ── D.2: sharpe-weighted ensemble reads legacy oos*-keyed winners.json entries ─────────────

  @Test
  void sharpeWeightedEnsemble_resolvesWeightsFromLegacyOosSpelledWinnersJson(@TempDir Path tmp)
      throws IOException {
    Path dataset =
        Path.of("src/test/resources/fixtures/large_snippets/BTCUSDT_1h_month.csv").toAbsolutePath();
    Path winnersPath = tmp.resolve("winners.json");
    // Simulates entries promoted before the cv* rename (PLAN-008 D.2) — oos* keys only, no cv*.
    Files.writeString(
        winnersPath,
        """
        {
          "BTCUSDT_emarsi_legacy": {
            "params": {"emaFast": 9, "emaSlow": 21, "rsiPeriod": 14,
                        "rsiOverbought": 70.0, "rsiOversold": 30.0, "spreadScale": 0.01},
            "oosSharpeMedian": 0.8
          },
          "BTCUSDT_meanrev_legacy": {
            "params": {"rsiPeriod": 14, "bbPeriod": 20, "bbStdevK": 2.0,
                        "rsiBuy": 30.0, "rsiSell": 70.0, "bandTouchTolerance": 0.05},
            "oosSharpeMedian": 0.3
          }
        }
        """);
    Path outDir = tmp.resolve("bt");

    BacktestCli.main(
        new String[] {
          "--strategy=ensemble",
          "--strategies=emarsi,meanrev",
          "--combiner=sharpe-weighted",
          "--ensemble-from-winners=BTCUSDT_<strategy>_legacy",
          "--winners=" + winnersPath,
          "--dataset=" + dataset,
          "--symbol=BTCUSDT",
          "--interval=ONE_HOUR",
          "--warmup-bars=100",
          "--risk=off",
          "--out=" + outDir
        });

    // No exception ⇒ the sharpe-weighted combiner successfully resolved weights via the
    // oos*-key fallback (Args.jsonFallback); a missing-key IllegalArgumentException would have
    // aborted main() before any output was written.
    assertThat(outDir.resolve("result.json")).exists();
  }

  // ── E: fee-scale and fee-mode ───────────────────────────────────────────────────────────────

  @Test
  void feeScale_producesMonotonicallyDecreasingTotalReturn(@TempDir Path tmp) throws IOException {
    Path dataset =
        Path.of("src/test/resources/fixtures/large_snippets/BTCUSDT_1h_month.csv").toAbsolutePath();

    java.math.BigDecimal[] returns = new java.math.BigDecimal[3];
    String[] scales = {"0", "1.0", "2.0"};
    for (int i = 0; i < scales.length; i++) {
      Path outDir = tmp.resolve("fs" + i);
      BacktestCli.main(
          new String[] {
            "--strategy=emarsi",
            "--dataset=" + dataset,
            "--symbol=BTCUSDT",
            "--interval=ONE_HOUR",
            "--warmup-bars=100",
            "--risk=off",
            "--fee-scale=" + scales[i],
            "--out=" + outDir
          });
      String result = Files.readString(outDir.resolve("result.json"));
      assertThat(result).contains("\"feeScale\": " + scales[i]).contains("\"feeMode\": \"taker\"");
      returns[i] = extractJsonNumber(result, "totalReturn");
    }

    assertThat(returns[0]).isGreaterThan(returns[1]);
    assertThat(returns[1]).isGreaterThan(returns[2]);
  }

  @Test
  void feeMode_makerDiffersFromTaker_bothRecordedInResult(@TempDir Path tmp) throws IOException {
    Path dataset =
        Path.of("src/test/resources/fixtures/large_snippets/BTCUSDT_1h_month.csv").toAbsolutePath();

    Path takerOut = tmp.resolve("taker");
    BacktestCli.main(
        new String[] {
          "--strategy=emarsi",
          "--dataset=" + dataset,
          "--symbol=BTCUSDT",
          "--interval=ONE_HOUR",
          "--warmup-bars=100",
          "--risk=off",
          "--fee-mode=taker",
          "--out=" + takerOut
        });
    Path makerOut = tmp.resolve("maker");
    BacktestCli.main(
        new String[] {
          "--strategy=emarsi",
          "--dataset=" + dataset,
          "--symbol=BTCUSDT",
          "--interval=ONE_HOUR",
          "--warmup-bars=100",
          "--risk=off",
          "--fee-mode=maker",
          "--out=" + makerOut
        });

    String takerResult = Files.readString(takerOut.resolve("result.json"));
    String makerResult = Files.readString(makerOut.resolve("result.json"));
    assertThat(takerResult).contains("\"feeMode\": \"taker\"");
    assertThat(makerResult).contains("\"feeMode\": \"maker\"");
    // Maker is strictly cheaper (2bps/0 slippage vs 5bps/5bps) ⇒ a better or equal total return.
    java.math.BigDecimal takerReturn = extractJsonNumber(takerResult, "totalReturn");
    java.math.BigDecimal makerReturn = extractJsonNumber(makerResult, "totalReturn");
    assertThat(makerReturn).isGreaterThan(takerReturn);
  }

  private static java.math.BigDecimal extractJsonNumber(String json, String field) {
    var m = java.util.regex.Pattern.compile("\"" + field + "\": (-?[0-9.eE+-]+)").matcher(json);
    if (!m.find()) {
      throw new AssertionError("field '" + field + "' not found in: " + json);
    }
    return new java.math.BigDecimal(m.group(1));
  }
}
