package buffeffects.core.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer, so the core needs no library and translates as it is.
 * Objects read as {@link LinkedHashMap} (key order kept), arrays as {@link ArrayList}, numbers as
 * {@link BigDecimal} (so the format's "at most 3 decimals" rule can be checked exactly), and
 * true, false and null as {@link Boolean} and {@code null}.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** Parses one JSON document. Duplicate keys, trailing text and bad syntax are errors. */
    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("unexpected text after the document");
        return v;
    }

    private JsonException err(String what) {
        int line = 1, col = 1;
        for (int k = 0; k < i && k < s.length(); k++) {
            if (s.charAt(k) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new JsonException(what + " at line " + line + ", column " + col);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private Object value() {
        if (i >= s.length()) throw err("unexpected end of text");
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return word("true", Boolean.TRUE);
            case 'f':
                return word("false", Boolean.FALSE);
            case 'n':
                return word("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("unexpected character '" + c + "'");
        }
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) throw err("unexpected word");
        i += w.length();
        return v;
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') throw err("expected a key");
            String k = string();
            ws();
            if (peek() != ':') throw err("expected ':'");
            i++;
            ws();
            if (m.containsKey(k)) throw err("duplicate key \"" + k + "\"");
            m.put(k, value());
            ws();
            char c = peek();
            i++;
            if (c == '}') return m;
            if (c != ',') throw err("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return a;
        }
        while (true) {
            ws();
            a.add(value());
            ws();
            char c = peek();
            i++;
            if (c == ']') return a;
            if (c != ',') throw err("expected ',' or ']'");
        }
    }

    private char peek() {
        if (i >= s.length()) throw err("unexpected end of text");
        return s.charAt(i);
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            char c = peek();
            i++;
            if (c == '"') return b.toString();
            if (c < 0x20) throw err("control character in a string");
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = peek();
            i++;
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw err("short \\u escape");
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException x) {
                        throw err("bad \\u escape");
                    }
                    i += 4;
                    break;
                default:
                    throw err("bad escape");
            }
        }
    }

    private BigDecimal number() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        try {
            return new BigDecimal(s.substring(start, i));
        } catch (NumberFormatException x) {
            i = start;
            throw err("bad number");
        }
    }

    // ------------------------------------------------------------------------------------------ writing

    /** Writes a value: maps, lists, strings, numbers, booleans and null. Pretty prints with two spaces. */
    public static String write(Object v, boolean pretty) {
        StringBuilder b = new StringBuilder();
        write(b, v, pretty, 0);
        if (pretty) b.append('\n');
        return b.toString();
    }

    private static void write(StringBuilder b, Object v, boolean pretty, int depth) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            quote(b, (String) v);
        } else if (v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof BigDecimal) {
            b.append(((BigDecimal) v).stripTrailingZeros().toPlainString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
            else b.append(BigDecimal.valueOf(d).stripTrailingZeros().toPlainString());
        } else if (v instanceof Number) {
            b.append(((Number) v).longValue());
        } else if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                nl(b, pretty, depth + 1);
                quote(b, String.valueOf(e.getKey()));
                b.append(pretty ? ": " : ":");
                write(b, e.getValue(), pretty, depth + 1);
            }
            nl(b, pretty, depth);
            b.append('}');
        } else if (v instanceof List) {
            List<?> a = (List<?>) v;
            if (a.isEmpty()) {
                b.append("[]");
                return;
            }
            b.append('[');
            for (int k = 0; k < a.size(); k++) {
                if (k > 0) b.append(',');
                nl(b, pretty, depth + 1);
                write(b, a.get(k), pretty, depth + 1);
            }
            nl(b, pretty, depth);
            b.append(']');
        } else {
            quote(b, v.toString());
        }
    }

    private static void nl(StringBuilder b, boolean pretty, int depth) {
        if (!pretty) return;
        b.append('\n');
        for (int k = 0; k < depth; k++) b.append("  ");
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    /** Escapes one key for a JSON Pointer (RFC 6901). */
    public static String pointerToken(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }
}
