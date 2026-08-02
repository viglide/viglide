package app.viglide.core.params;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON serializer for the flat shapes the CLI emits (backtest results, manifest, top-K
 * calibration table). Avoids a Jackson dependency so {@code viglide-strategies} stays library-only.
 *
 * <p>Supported value types: {@link String}, {@link Boolean}, {@link Number}, {@link BigDecimal},
 * {@link Map}, {@link List}, and {@code null}. Strings are escaped per RFC 8259; numbers and
 * BigDecimals are emitted as plain decimals (no scientific notation).
 */
public final class JsonWriter {

  private JsonWriter() {}

  /** Serializes {@code value} as a pretty-printed JSON string with two-space indent. */
  public static String pretty(Object value) {
    StringBuilder sb = new StringBuilder();
    write(value, sb, 0, true);
    return sb.toString();
  }

  /** Serializes {@code value} as compact JSON (single line). */
  public static String compact(Object value) {
    StringBuilder sb = new StringBuilder();
    write(value, sb, 0, false);
    return sb.toString();
  }

  // ── implementation ───────────────────────────────────────────────────────────────────────────

  private static void write(Object v, StringBuilder sb, int depth, boolean pretty) {
    switch (v) {
      case null -> sb.append("null");
      case String s -> writeString(s, sb);
      case Boolean b -> sb.append(b.toString());
      case BigDecimal bd -> sb.append(bd.toPlainString());
      case Number n -> sb.append(n.toString());
      case Map<?, ?> m -> writeObject(m, sb, depth, pretty);
      case List<?> l -> writeArray(l, sb, depth, pretty);
      default ->
          throw new IllegalArgumentException(
              "unsupported JSON value type: " + v.getClass().getName());
    }
  }

  private static void writeObject(Map<?, ?> m, StringBuilder sb, int depth, boolean pretty) {
    if (m.isEmpty()) {
      sb.append("{}");
      return;
    }
    sb.append('{');
    boolean first = true;
    for (Map.Entry<?, ?> e : m.entrySet()) {
      if (!first) sb.append(',');
      if (pretty) sb.append('\n').append(indent(depth + 1));
      writeString(e.getKey().toString(), sb);
      sb.append(pretty ? ": " : ":");
      write(e.getValue(), sb, depth + 1, pretty);
      first = false;
    }
    if (pretty) sb.append('\n').append(indent(depth));
    sb.append('}');
  }

  private static void writeArray(List<?> l, StringBuilder sb, int depth, boolean pretty) {
    if (l.isEmpty()) {
      sb.append("[]");
      return;
    }
    sb.append('[');
    boolean first = true;
    for (Object item : l) {
      if (!first) sb.append(',');
      if (pretty) sb.append('\n').append(indent(depth + 1));
      write(item, sb, depth + 1, pretty);
      first = false;
    }
    if (pretty) sb.append('\n').append(indent(depth));
    sb.append(']');
  }

  private static void writeString(String s, StringBuilder sb) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  private static String indent(int depth) {
    return "  ".repeat(depth);
  }
}
