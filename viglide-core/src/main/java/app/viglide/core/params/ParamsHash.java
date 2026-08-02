package app.viglide.core.params;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * A short, stable, iteration-order-independent hash of a parameter map (PLAN-012 Task C, review
 * finding F3) — for logging and audit-trail provenance ("which parameters produced this run")
 * without ever printing the values themselves. {@code winners.json} parameters are treated as
 * secrets (CLAUDE.md §8): a hash lets two runs be compared for "same parameters or different" and
 * lets an operator spot a mismatch, without the log line or ledger ever carrying a tunable value.
 *
 * <p>Two calls with the same {@code (key, value)} pairs produce the same hash regardless of the
 * input map's iteration order — entries are sorted by key before hashing.
 */
public final class ParamsHash {

  private static final int DISPLAY_HEX_CHARS = 12;

  private ParamsHash() {}

  /** Returns a {@value #DISPLAY_HEX_CHARS}-hex-character SHA-256 prefix of the sorted params. */
  public static String of(Map<String, String> params) {
    Map<String, String> sorted = new TreeMap<>(params);
    StringBuilder canonical = new StringBuilder();
    for (Map.Entry<String, String> e : sorted.entrySet()) {
      canonical.append(e.getKey()).append('=').append(e.getValue()).append(';');
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.substring(0, DISPLAY_HEX_CHARS);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is a JDK-mandatory algorithm (every conforming JVM ships it) -- this cannot
      // actually happen, but MessageDigest.getInstance's checked exception forces a handler.
      throw new IllegalStateException("SHA-256 MessageDigest unavailable", e);
    }
  }
}
