package app.viglide.core.params;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A flat {@code Map<String,String>} params reader, plus the camelCase-to-kebab-case conversion for
 * a winners-store entry. Split out of the research CLIs' {@code Args} class (PLAN-018 R-2.2): every
 * {@code StrategyProvider#create} implementation — public ({@code viglide-examples}) or private
 * ({@code viglide-strategies}) — needs the same "typed value or fallback" accessors {@code Args}
 * already had, and {@code viglide-runtime}'s {@code PaperTradingRunner} needs {@link #parse}/{@link
 * #require} for its own {@code --mode=paper} argv — but none of the three may depend on {@code
 * viglide-research} (a CLI/calibration-tooling module, not something a production deployable or
 * another public module should pull in). Deliberately duplicates (not delegates to) {@code Args}'s
 * equivalent methods rather than introducing that dependency for ~20 lines of trivial parsing;
 * {@link WinnersParamsLoader} needs {@link #camelMapToCliArgs} for the same reason.
 */
public final class CliArgs {

  private CliArgs() {}

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

  public static double doubleOpt(Map<String, String> args, String key, double fallback) {
    String v = args.get(key);
    return (v == null || v.isBlank()) ? fallback : Double.parseDouble(v);
  }

  public static BigDecimal bigDecOpt(Map<String, String> args, String key, BigDecimal fallback) {
    String v = args.get(key);
    return (v == null || v.isBlank()) ? fallback : new BigDecimal(v);
  }

  public static Map<String, String> camelMapToCliArgs(Map<String, Object> params) {
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : params.entrySet()) {
      Object v = e.getValue();
      out.put(camelToKebab(e.getKey()), v == null ? "" : v.toString());
    }
    return out;
  }

  private static String camelToKebab(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 4);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isUpperCase(c)) {
        if (i > 0) sb.append('-');
        sb.append(Character.toLowerCase(c));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
