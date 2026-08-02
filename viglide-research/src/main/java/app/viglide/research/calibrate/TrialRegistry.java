package app.viglide.research.calibrate;

import app.viglide.core.backtest.Metrics;
import app.viglide.core.params.JsonReader;
import app.viglide.core.params.JsonWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cumulative, append-only record of every calibration run ever made against a given dataset
 * (PLAN-013 Task H, review finding F4). {@link Metrics#deflatedSharpeRatio}'s trial correction is
 * only as honest as the trial count fed into it — {@code CalibrationHarness} supplies the candidate
 * count <em>within one run</em> (typically 300), but the true search effort against a given
 * pair/year/interval is every run ever made against it, across every plan that has touched this
 * dataset (PLAN-008/009/010/011 and onward). A single-run trial count corrects for a fraction of a
 * percent of the actual multiple testing.
 *
 * <p><strong>The fingerprint identifies the data, not the run</strong> (the trap this class exists
 * to avoid): two runs over the same symbol/interval/dataset file must accumulate into the same
 * total even with different seeds, search modes, or objectives — {@link #datasetFingerprint} hashes
 * the dataset file's own bytes (not its path, which can move, and not anything run-specific)
 * alongside the symbol and interval.
 */
public final class TrialRegistry {

  private static final int FINGERPRINT_HEX_CHARS = 16;

  private TrialRegistry() {}

  /**
   * SHA-256 of {@code symbol|interval|sha256(datasetPath's own bytes)}, truncated to {@value
   * #FINGERPRINT_HEX_CHARS} hex characters. Two calibration runs against the identical dataset file
   * (byte-for-byte — a regenerated-but-equivalent CSV would fingerprint differently, which is
   * intentional: this identifies exactly which data trials were spent against, not an approximation
   * of it) produce the same fingerprint regardless of seed, search mode, or objective.
   */
  public static String datasetFingerprint(String symbol, String interval, Path datasetPath) {
    try {
      byte[] fileBytes = Files.readAllBytes(datasetPath);
      String fileHash = sha256Hex(fileBytes);
      String canonical = symbol + "|" + interval + "|" + fileHash;
      return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8))
          .substring(0, FINGERPRINT_HEX_CHARS);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to fingerprint dataset " + datasetPath, e);
    }
  }

  /** One completed calibration run, as recorded in the registry. */
  public record TrialRecord(
      Instant timestamp,
      String strategy,
      String datasetFingerprint,
      int candidateCount,
      long seed,
      String objective) {}

  /**
   * Appends one line to {@code registryPath} (creating it, and any missing parent directories, if
   * absent) — never truncates, never rewrites an existing line (append-only, matching {@code
   * docs/notes/}'s own convention for exactly the same "history must not be rewritable" reason).
   */
  public static void append(Path registryPath, TrialRecord record) {
    try {
      if (registryPath.getParent() != null) {
        Files.createDirectories(registryPath.getParent());
      }
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("timestamp", record.timestamp().toString());
      line.put("strategy", record.strategy());
      line.put("datasetFingerprint", record.datasetFingerprint());
      line.put("candidateCount", record.candidateCount());
      line.put("seed", record.seed());
      line.put("objective", record.objective());
      String jsonLine = JsonWriter.compact(line) + System.lineSeparator();
      Files.writeString(
          registryPath,
          jsonLine,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to append to trial registry " + registryPath, e);
    }
  }

  /**
   * Sum of {@code candidateCount} across every registry entry matching {@code datasetFingerprint} —
   * the cumulative trial count {@link Metrics#deflatedSharpeRatio} should actually be deflated by.
   * {@code 0} (not an error) when the registry does not exist yet — the very first run against a
   * dataset has no prior trials to accumulate.
   */
  @SuppressWarnings("unchecked")
  public static int cumulativeTrialsFor(Path registryPath, String datasetFingerprint) {
    if (!Files.exists(registryPath)) {
      return 0;
    }
    try {
      List<String> lines = Files.readAllLines(registryPath, StandardCharsets.UTF_8);
      int total = 0;
      for (String line : lines) {
        if (line.isBlank()) {
          continue;
        }
        Map<String, Object> parsed = (Map<String, Object>) JsonReader.parse(line);
        if (datasetFingerprint.equals(parsed.get("datasetFingerprint"))) {
          total += ((Number) parsed.get("candidateCount")).intValue();
        }
      }
      return total;
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read trial registry " + registryPath, e);
    }
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(bytes);
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 MessageDigest unavailable", e);
    }
  }
}
