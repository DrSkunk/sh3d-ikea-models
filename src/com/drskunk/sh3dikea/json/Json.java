package com.drskunk.sh3dikea.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser. Produces nested {@link Map}, {@link List}, {@link String},
 * {@link Number}, {@link Boolean}, or {@code null}. Numbers are returned as
 * {@link Double} for fractional / exponential values, or {@link Long} for integers.
 *
 * Not suitable for production use beyond this plugin's needs — no streaming,
 * no schema, no fancy errors. But it is enough to read IKEA's API responses
 * and the JSON chunk of a GLB file without pulling in a dependency.
 */
public final class Json {

    private final String s;
    private int p;

    private Json(String s) { this.s = s; this.p = 0; }

    public static Object parse(String s) {
        Json j = new Json(s);
        j.skipWs();
        Object v = j.readValue();
        j.skipWs();
        if (j.p != s.length()) {
            throw new IllegalArgumentException("Trailing content at offset " + j.p);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object o) {
        if (o == null) return null;
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new IllegalArgumentException("Expected object, got " + o.getClass().getSimpleName());
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object o) {
        if (o == null) return null;
        if (o instanceof List) return (List<Object>) o;
        throw new IllegalArgumentException("Expected array, got " + o.getClass().getSimpleName());
    }

    public static String asString(Object o) {
        if (o == null) return null;
        if (o instanceof String) return (String) o;
        return o.toString();
    }

    public static int asInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).intValue();
        return def;
    }

    public static double asDouble(Object o, double def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).doubleValue();
        return def;
    }

    public static boolean asBool(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean) return (Boolean) o;
        return def;
    }

    private Object readValue() {
        skipWs();
        if (p >= s.length()) throw err("Unexpected end of input");
        char c = s.charAt(p);
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': case 'f': return readBool();
            case 'n': return readNull();
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
                throw err("Unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> m = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') { p++; return m; }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            expect(':');
            Object v = readValue();
            m.put(key, v);
            skipWs();
            char c = next();
            if (c == ',') continue;
            if (c == '}') return m;
            throw err("Expected ',' or '}'");
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> l = new ArrayList<>();
        skipWs();
        if (peek() == ']') { p++; return l; }
        while (true) {
            l.add(readValue());
            skipWs();
            char c = next();
            if (c == ',') continue;
            if (c == ']') return l;
            throw err("Expected ',' or ']'");
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (p >= s.length()) throw err("Unterminated string");
            char c = s.charAt(p++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (p >= s.length()) throw err("Bad escape");
                char e = s.charAt(p++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (p + 4 > s.length()) throw err("Bad unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                        p += 4;
                        break;
                    default: throw err("Bad escape \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Boolean readBool() {
        if (s.startsWith("true", p)) { p += 4; return Boolean.TRUE; }
        if (s.startsWith("false", p)) { p += 5; return Boolean.FALSE; }
        throw err("Expected true/false");
    }

    private Object readNull() {
        if (s.startsWith("null", p)) { p += 4; return null; }
        throw err("Expected null");
    }

    private Number readNumber() {
        int start = p;
        if (peek() == '-') p++;
        while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
        boolean fractional = false;
        if (p < s.length() && s.charAt(p) == '.') {
            fractional = true; p++;
            while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
        }
        if (p < s.length() && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) {
            fractional = true; p++;
            if (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')) p++;
            while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
        }
        String num = s.substring(start, p);
        if (fractional) return Double.parseDouble(num);
        try {
            return Long.parseLong(num);
        } catch (NumberFormatException e) {
            return Double.parseDouble(num);
        }
    }

    private void skipWs() {
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') p++;
            else break;
        }
    }

    private char peek() {
        if (p >= s.length()) throw err("Unexpected end of input");
        return s.charAt(p);
    }

    private char next() {
        if (p >= s.length()) throw err("Unexpected end of input");
        return s.charAt(p++);
    }

    private void expect(char c) {
        if (p >= s.length() || s.charAt(p) != c) throw err("Expected '" + c + "'");
        p++;
    }

    private RuntimeException err(String msg) {
        return new IllegalArgumentException(msg + " at offset " + p);
    }
}
