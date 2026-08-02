package app.viglide.core.params;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader paired with {@link JsonWriter}. Sufficient for round-tripping the flat shapes
 * this codebase emits — does not pretend to be RFC-8259 complete. Numbers come back as {@link
 * BigDecimal}; objects as ordered {@link LinkedHashMap}; arrays as {@link ArrayList}; strings,
 * booleans and {@code null} as their Java equivalents.
 */
public final class JsonReader {

  private final String src;
  private int pos;

  private JsonReader(String src) {
    this.src = src;
    this.pos = 0;
  }

  /** Parses a JSON document and returns the root value (Map, List, String, BigDecimal, …). */
  public static Object parse(String json) {
    JsonReader r = new JsonReader(json);
    r.skipWs();
    Object root = r.readValue();
    r.skipWs();
    if (r.pos != r.src.length()) {
      throw new IllegalArgumentException("trailing characters at offset " + r.pos);
    }
    return root;
  }

  // ── core read loop ───────────────────────────────────────────────────────────────────────────

  private Object readValue() {
    skipWs();
    if (pos >= src.length()) throw new IllegalArgumentException("unexpected end of JSON");
    char c = src.charAt(pos);
    if (c == '{') return readObject();
    if (c == '[') return readArray();
    if (c == '"') return readString();
    if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
    if (src.startsWith("true", pos)) {
      pos += 4;
      return Boolean.TRUE;
    }
    if (src.startsWith("false", pos)) {
      pos += 5;
      return Boolean.FALSE;
    }
    if (src.startsWith("null", pos)) {
      pos += 4;
      return null;
    }
    throw new IllegalArgumentException("unexpected character '" + c + "' at offset " + pos);
  }

  private Map<String, Object> readObject() {
    expect('{');
    Map<String, Object> map = new LinkedHashMap<>();
    skipWs();
    if (peek() == '}') {
      pos++;
      return map;
    }
    while (true) {
      skipWs();
      String key = readString();
      skipWs();
      expect(':');
      Object value = readValue();
      map.put(key, value);
      skipWs();
      char c = src.charAt(pos);
      if (c == ',') {
        pos++;
        continue;
      }
      if (c == '}') {
        pos++;
        return map;
      }
      throw new IllegalArgumentException("expected ',' or '}' at offset " + pos);
    }
  }

  private List<Object> readArray() {
    expect('[');
    List<Object> list = new ArrayList<>();
    skipWs();
    if (peek() == ']') {
      pos++;
      return list;
    }
    while (true) {
      list.add(readValue());
      skipWs();
      char c = src.charAt(pos);
      if (c == ',') {
        pos++;
        continue;
      }
      if (c == ']') {
        pos++;
        return list;
      }
      throw new IllegalArgumentException("expected ',' or ']' at offset " + pos);
    }
  }

  private String readString() {
    expect('"');
    StringBuilder sb = new StringBuilder();
    while (pos < src.length()) {
      char c = src.charAt(pos++);
      if (c == '"') return sb.toString();
      if (c == '\\') {
        char esc = src.charAt(pos++);
        switch (esc) {
          case '"' -> sb.append('"');
          case '\\' -> sb.append('\\');
          case '/' -> sb.append('/');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'u' -> {
            String hex = src.substring(pos, pos + 4);
            pos += 4;
            sb.append((char) Integer.parseInt(hex, 16));
          }
          default -> throw new IllegalArgumentException("bad escape \\" + esc);
        }
      } else {
        sb.append(c);
      }
    }
    throw new IllegalArgumentException("unterminated string");
  }

  private BigDecimal readNumber() {
    int start = pos;
    if (src.charAt(pos) == '-') pos++;
    while (pos < src.length()) {
      char c = src.charAt(pos);
      if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
        pos++;
      } else {
        break;
      }
    }
    return new BigDecimal(src.substring(start, pos));
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────────

  private void skipWs() {
    while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
  }

  private void expect(char c) {
    if (pos >= src.length() || src.charAt(pos) != c) {
      throw new IllegalArgumentException(
          "expected '"
              + c
              + "' at offset "
              + pos
              + " (got '"
              + (pos < src.length() ? src.charAt(pos) : "EOF")
              + "')");
    }
    pos++;
  }

  private char peek() {
    return pos < src.length() ? src.charAt(pos) : '\0';
  }
}
