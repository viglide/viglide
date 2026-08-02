package app.viglide.research.calibrate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link TrialRegistry} (PLAN-013 Task H, review finding F4). */
class TrialRegistryTest {

  @Test
  void datasetFingerprint_sameFileSymbolInterval_sameFingerprint(@TempDir Path dir)
      throws IOException {
    Path dataset = dir.resolve("data.csv");
    Files.writeString(dataset, "timestamp,open,high,low,close,volume\n1,1,1,1,1,1\n");
    String fp1 = TrialRegistry.datasetFingerprint("BTCUSDT", "ONE_HOUR", dataset);
    String fp2 = TrialRegistry.datasetFingerprint("BTCUSDT", "ONE_HOUR", dataset);
    assertThat(fp1).isEqualTo(fp2);
  }

  @Test
  void datasetFingerprint_differentFileContent_differentFingerprint(@TempDir Path dir)
      throws IOException {
    Path a = dir.resolve("a.csv");
    Path b = dir.resolve("b.csv");
    Files.writeString(a, "timestamp,open,high,low,close,volume\n1,1,1,1,1,1\n");
    Files.writeString(b, "timestamp,open,high,low,close,volume\n2,2,2,2,2,2\n");
    String fpA = TrialRegistry.datasetFingerprint("BTCUSDT", "ONE_HOUR", a);
    String fpB = TrialRegistry.datasetFingerprint("BTCUSDT", "ONE_HOUR", b);
    assertThat(fpA).isNotEqualTo(fpB);
  }

  @Test
  void datasetFingerprint_differentSymbolSameFile_differentFingerprint(@TempDir Path dir)
      throws IOException {
    Path dataset = dir.resolve("data.csv");
    Files.writeString(dataset, "timestamp,open,high,low,close,volume\n1,1,1,1,1,1\n");
    String fpBtc = TrialRegistry.datasetFingerprint("BTCUSDT", "ONE_HOUR", dataset);
    String fpEth = TrialRegistry.datasetFingerprint("ETHUSDT", "ONE_HOUR", dataset);
    assertThat(fpBtc).isNotEqualTo(fpEth);
  }

  @Test
  void datasetFingerprint_differentIntervalSameFile_differentFingerprint(@TempDir Path dir)
      throws IOException {
    Path dataset = dir.resolve("data.csv");
    Files.writeString(dataset, "timestamp,open,high,low,close,volume\n1,1,1,1,1,1\n");
    String fp1h = TrialRegistry.datasetFingerprint("BTCUSDT", "ONE_HOUR", dataset);
    String fp15m = TrialRegistry.datasetFingerprint("BTCUSDT", "FIFTEEN_MINUTES", dataset);
    assertThat(fp1h).isNotEqualTo(fp15m);
  }

  @Test
  void cumulativeTrialsFor_noRegistryYet_isZeroNotAnError(@TempDir Path dir) {
    Path registry = dir.resolve("does-not-exist.jsonl");
    assertThat(TrialRegistry.cumulativeTrialsFor(registry, "anyfingerprint")).isZero();
  }

  @Test
  void acceptanceCriterion_twoSuccessiveRunsOverSameData_accumulateTo600Not300(@TempDir Path dir) {
    // The acceptance criterion, verbatim: two successive calibration runs over the same data
    // produce a DSR in the second run deflated by ~600 trials, not 300.
    Path registry = dir.resolve("research-trials.jsonl");
    String fingerprint = "abc123fingerprint";

    TrialRegistry.append(
        registry,
        new TrialRegistry.TrialRecord(
            Instant.parse("2026-01-01T00:00:00Z"),
            "fundingarb",
            fingerprint,
            300,
            1L,
            "carry-yield"));
    int afterFirstRun = TrialRegistry.cumulativeTrialsFor(registry, fingerprint);
    assertThat(afterFirstRun).isEqualTo(300);

    // Second run: different seed, different objective -- must still accumulate against the SAME
    // fingerprint (the trap: fingerprint identifies the DATA, not the run).
    TrialRegistry.append(
        registry,
        new TrialRegistry.TrialRecord(
            Instant.parse("2026-01-02T00:00:00Z"),
            "fundingarb",
            fingerprint,
            300,
            999L,
            "median-cv-sharpe"));
    int afterSecondRun = TrialRegistry.cumulativeTrialsFor(registry, fingerprint);
    assertThat(afterSecondRun).isEqualTo(600);
  }

  @Test
  void cumulativeTrialsFor_differentFingerprint_doesNotAccumulate(@TempDir Path dir) {
    Path registry = dir.resolve("research-trials.jsonl");
    TrialRegistry.append(
        registry,
        new TrialRegistry.TrialRecord(
            Instant.now(), "fundingarb", "fingerprintA", 300, 1L, "carry-yield"));
    TrialRegistry.append(
        registry,
        new TrialRegistry.TrialRecord(
            Instant.now(), "fundingarb", "fingerprintB", 500, 2L, "carry-yield"));

    assertThat(TrialRegistry.cumulativeTrialsFor(registry, "fingerprintA")).isEqualTo(300);
    assertThat(TrialRegistry.cumulativeTrialsFor(registry, "fingerprintB")).isEqualTo(500);
  }

  @Test
  void registry_survivesAcrossInvocations_appendIsTrulyPersisted(@TempDir Path dir) {
    // Simulates two separate process runs: the second "invocation" only calls cumulativeTrialsFor
    // after the first has already returned -- proving persistence isn't just an in-memory artifact
    // of a single append+read call sequence.
    Path registry = dir.resolve("research-trials.jsonl");
    TrialRegistry.append(
        registry,
        new TrialRegistry.TrialRecord(Instant.now(), "emarsi", "fp", 300, 1L, "carry-yield"));

    // "Invocation 2": fresh read, nothing carried over except what's on disk.
    int cumulative = TrialRegistry.cumulativeTrialsFor(registry, "fp");
    assertThat(cumulative).isEqualTo(300);
    assertThat(Files.exists(registry)).isTrue();
  }
}
