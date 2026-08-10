package app.viglide.research.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.viglide.core.params.JsonReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PLAN-023 Task B: ADR-0016 condition 6 for a cross-sectional book. Until this existed the
 * matched-turnover baseline the pre-registration names by symbol was a package-private test
 * fixture, so a K1′ verdict for {@code carryranking} could not be produced at all — only a number.
 */
class PortfolioNullModelCliTest {

  @Test
  void producesAPercentileAndTheConditionSixBoolean(@TempDir Path tmp) throws IOException {
    Path data = tmp.resolve("data");
    Files.createDirectories(data);
    for (String pair : List.of("AAA", "BBB", "CCC", "DDD")) {
      Files.writeString(data.resolve(pair + "_1h_2024.csv"), klines(300));
      Files.writeString(data.resolve(pair + "_spot_1h_2024.csv"), klines(300));
      Files.writeString(data.resolve(pair + "_funding_2024.csv"), funding(300));
    }
    Path outDir = tmp.resolve("out");

    int exit =
        PortfolioNullModelCli.run(
            new String[] {
              "--pairs=AAA,BBB,CCC,DDD",
              "--strategy=fixture-carry-cli",
              "--label=2024",
              "--datasets-dir=" + data,
              "--k=2",
              "--params=variant=a",
              "--min-funding-events=6",
              "--n=12", // ADR-0016 pre-registers 200; a smoke test does not need to pay for it
              "--seed=42",
              "--warmup-bars=20",
              "--out=" + outDir
            });

    assertThat(exit).isZero();
    @SuppressWarnings("unchecked")
    Map<String, Object> m =
        (Map<String, Object>) JsonReader.parse(Files.readString(outDir.resolve("null-model.json")));
    assertThat(((Number) m.get("n")).intValue()).isEqualTo(12);
    assertThat((List<?>) m.get("permutationReturns")).hasSize(12);
    double pct = ((Number) m.get("percentileOfActual")).doubleValue();
    assertThat(pct).isBetween(0.0, 1.0);
    // The gate reads a boolean; emitting it here means a verdict note never has to re-derive the
    // comparison from the distribution and risk getting its direction backwards.
    assertThat(m).containsKey("beatsP95");
    assertThat(((Number) m.get("nullP95")).doubleValue())
        .isGreaterThanOrEqualTo(((Number) m.get("nullMedian")).doubleValue());
  }

  @Test
  void isReproducibleFromItsSeed(@TempDir Path tmp) throws IOException {
    Path data = tmp.resolve("data");
    Files.createDirectories(data);
    for (String pair : List.of("AAA", "BBB", "CCC")) {
      Files.writeString(data.resolve(pair + "_1h_2024.csv"), klines(200));
      Files.writeString(data.resolve(pair + "_spot_1h_2024.csv"), klines(200));
      Files.writeString(data.resolve(pair + "_funding_2024.csv"), funding(200));
    }

    Path a = tmp.resolve("a");
    Path b = tmp.resolve("b");
    for (Path out : List.of(a, b)) {
      PortfolioNullModelCli.run(
          new String[] {
            "--pairs=AAA,BBB,CCC",
            "--strategy=fixture-carry-cli",
            "--label=2024",
            "--datasets-dir=" + data,
            "--k=2",
            "--params=variant=a",
            "--n=8",
            "--seed=99",
            "--warmup-bars=20",
            "--out=" + out
          });
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> ra =
        (Map<String, Object>) JsonReader.parse(Files.readString(a.resolve("null-model.json")));
    @SuppressWarnings("unchecked")
    Map<String, Object> rb =
        (Map<String, Object>) JsonReader.parse(Files.readString(b.resolve("null-model.json")));
    assertThat(rb.get("permutationReturns")).isEqualTo(ra.get("permutationReturns"));
    assertThat(rb.get("actualReturn")).isEqualTo(ra.get("actualReturn"));
  }

  @Test
  void refusesAnUnderSpecifiedSelectorRatherThanPickingArbitrarily(@TempDir Path tmp)
      throws IOException {
    Path data = tmp.resolve("data");
    Files.createDirectories(data);
    for (String pair : List.of("AAA", "BBB")) {
      Files.writeString(data.resolve(pair + "_1h_2024.csv"), klines(120));
      Files.writeString(data.resolve(pair + "_spot_1h_2024.csv"), klines(120));
      Files.writeString(data.resolve(pair + "_funding_2024.csv"), funding(120));
    }

    // The fixture grid has two candidates; a selector matching both must be rejected, because
    // silently taking the first would make the comparison unreproducible from the command.
    assertThatThrownBy(
            () ->
                PortfolioNullModelCli.run(
                    new String[] {
                      "--pairs=AAA,BBB",
                      "--strategy=fixture-carry-cli",
                      "--label=2024",
                      "--datasets-dir=" + data,
                      "--k=2",
                      "--params=unrelated=x",
                      "--n=2",
                      "--warmup-bars=20",
                      "--out=" + tmp.resolve("out")
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly one candidate");
  }

  private static String klines(int bars) {
    StringBuilder sb = new StringBuilder();
    Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
    for (int i = 0; i < bars; i++) {
      int p = 100 + (i % 5);
      sb.append(t0.plusSeconds(3600L * i).toEpochMilli())
          .append(',')
          .append(p)
          .append(',')
          .append(p + 1)
          .append(',')
          .append(p - 1)
          .append(',')
          .append(p)
          .append(",1000000\n");
    }
    return sb.toString();
  }

  private static String funding(int bars) {
    StringBuilder sb = new StringBuilder();
    Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
    int e = 0;
    for (int i = 0; i < bars; i += 8, e++) {
      sb.append(t0.plusSeconds(3600L * i).toEpochMilli())
          .append(",0.000")
          .append((e % 6) + 1)
          .append('\n');
    }
    return sb.toString();
  }
}
