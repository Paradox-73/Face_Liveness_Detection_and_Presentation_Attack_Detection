package io.mosip.liveness.onnx.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal JSON reader and writer for the model manifest and the signature header, so the module needs no JSON
 * library on either platform.
 *
 * <p>Reading returns {@link Map} (insertion order kept), {@link List}, {@link String}, {@link Long} (integers),
 * {@link Double}, {@link Boolean} or {@code null}. Nesting is limited to 16 levels and duplicate keys are rejected.
 */
final class Json {

    private static final int MAX_DEPTH = 16;

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /**
     * Parses a JSON document.
     *
     * @param text the document
     * @return the value
     * @throws IllegalArgumentException if the text is not valid JSON
     */
    static Object parse(String text) {
        Json p = new Json(text);
        p.skipSpace();
        Object value = p.value(0);
        p.skipSpace();
        if (p.pos != text.length()) {
            throw p.error("trailing characters");
        }
        return value;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nesting too deep");
        }
        if (pos >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                return number();
        }
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipSpace();
        if (peek('}')) {
            pos++;
            return Collections.unmodifiableMap(map);
        }
        while (true) {
            skipSpace();
            if (!peek('"')) {
                throw error("object key expected");
            }
            String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            if (map.containsKey(key)) {
                throw error("duplicate key " + key);
            }
            map.put(key, value(depth + 1));
            skipSpace();
            if (peek(',')) {
                pos++;
                continue;
            }
            expect('}');
            return Collections.unmodifiableMap(map);
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        pos++;
        skipSpace();
        if (peek(']')) {
            pos++;
            return Collections.unmodifiableList(list);
        }
        while (true) {
            skipSpace();
            list.add(value(depth + 1));
            skipSpace();
            if (peek(',')) {
                pos++;
                continue;
            }
            expect(']');
            return Collections.unmodifiableList(list);
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw error("control character in string");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw error("unterminated escape");
            }
            char e = text.charAt(pos++);
            switch (e) {
                case '"':
                case '\\':
                case '/':
                    sb.append(e);
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    sb.append(unicodeEscape());
                    break;
                default:
                    throw error("bad escape");
            }
        }
    }

    private char unicodeEscape() {
        if (pos + 4 > text.length()) {
            throw error("bad unicode escape");
        }
        try {
            char c = (char) Integer.parseInt(text.substring(pos, pos + 4), 16);
            pos += 4;
            return c;
        } catch (NumberFormatException ex) {
            throw error("bad unicode escape");
        }
    }

    private Object number() {
        int start = pos;
        if (peek('-')) {
            pos++;
        }
        boolean fraction = false;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                fraction = true;
                pos++;
            } else {
                break;
            }
        }
        String s = text.substring(start, pos);
        if (s.isEmpty() || "-".equals(s)) {
            throw error("value expected");
        }
        try {
            if (!fraction) {
                return Long.parseLong(s);
            }
            double d = Double.parseDouble(s);
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw error("number out of range");
            }
            return d;
        } catch (NumberFormatException ex) {
            throw error("bad number " + s);
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("unknown literal");
        }
        pos += word.length();
        return value;
    }

    private void skipSpace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private boolean peek(char c) {
        return pos < text.length() && text.charAt(pos) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw error("'" + c + "' expected");
        }
        pos++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("invalid JSON at offset " + pos + ": " + what);
    }

    /**
     * Writes a string as a JSON string literal.
     *
     * @param s  the string
     * @param sb destination
     */
    static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }
}
