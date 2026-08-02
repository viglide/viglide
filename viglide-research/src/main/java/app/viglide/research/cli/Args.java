package app.viglide.research.cli;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal command-line argument parser. Accepts only long-form flags of the shape {@code
 * --key=value} (or {@code --key} for booleans). No positional args, no short flags — keeps the
 * parser tiny and the CLI surface unambiguous.
 */
public final class Args {

  private Args() {}

  /** Parses {@code --k=v} args into an order-preserving map. */
  public static Map<String, String> parse(String[] argv) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String a : argv) {
      if (a == null || a.isBlank()) continue;
      if (!a.startsWith("--")) {
        throw new IllegalArgumentException(
            "expected --key=value, got: '" + a + "' (positional args not supported)");
      }
      String body = a.substring(2);
      int eq = body.indexOf('=');
      if (eq < 0) {
        out.put(body, "true"); // bare --flag ⇒ "true"
      } else {
        out.put(body.substring(0, eq), body.substring(eq + 1));
      }
    }
    return out;
  }

  public static String require(Map<String, String> args, String key) {
    String v = args.get(key);
    if (v == null || v.isBlank()) {
      throw new IllegalArgumentException("--" + key + " is required");
    }
    return v;
  }

  public static String opt(Map<String, String> args, String key, String fallback) {
    String v = args.get(key);
    return (v == null || v.isBlank()) ? fallback : v;
  }

  public static int intOpt(Map<String, String> args, String key, int fallback) {
    String v = args.get(key);
    return (v == null || v.isBlank()) ? fallback : Integer.parseInt(v);
  }

  public static long longOpt(Map<String, String> args, String key, long fallback) {
    String v = args.get(key);
    return (v == null || v.isBlank()) ? fallback : Long.parseLong(v);
  }

  public static double doubleOpt(Map<String, String> args, String key, double fallback) {
    String v = args.get(key);
    return (v == null || v.isBlank()) ? fallback : Double.parseDouble(v);
  }

  public static BigDecimal bigDecOpt(Map<String, String> args, String key, BigDecimal fallback) {
    String v = args.get(key);
    return (v == null || v.isBlank()) ? fallback : new BigDecimal(v);
  }

  public static boolean flag(Map<String, String> args, String key) {
    return "true".equalsIgnoreCase(args.get(key));
  }

  /**
   * Reads a field from a parsed JSON object, preferring {@code preferredKey} and falling back to
   * {@code legacyKey} when the preferred key is absent (PLAN-008 Task D.2: the {@code oos*} to
   * {@code cv*} field rename must not break {@code winners.json} entries written before the
   * rename).
   */
  public static Object jsonFallback(
      Map<String, Object> obj, String preferredKey, String legacyKey) {
    return obj.containsKey(preferredKey) ? obj.get(preferredKey) : obj.get(legacyKey);
  }
}
