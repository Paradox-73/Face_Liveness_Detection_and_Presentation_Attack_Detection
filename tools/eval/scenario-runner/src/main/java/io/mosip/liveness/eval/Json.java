package io.mosip.liveness.eval;

import java.util.Collection;
import java.util.Map;

/**
 * Minimal JSON serialiser for the runner's output records: maps, collections, strings, numbers, booleans, enums
 * and {@code null}. Non-finite numbers become {@code null}. Keeps the runner free of a JSON library.
 */
final class Json {

    private Json() {
    }

    /**
     * Serialises a value on one line.
     *
     * @param value a map, collection, string, number, boolean, enum or {@code null}
     * @return the JSON text
     * @throws IllegalArgumentException for any other type
     */
    static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            appendString(sb, (String) value);
        } else if (value instanceof Enum) {
            appendString(sb, ((Enum<?>) value).name());
        } else if (value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof Double || value instanceof Float) {
            appendDouble(sb, ((Number) value).doubleValue());
        } else if (value instanceof Number) {
            sb.append(value.toString());
        } else if (value instanceof Map) {
            appendMap(sb, (Map<?, ?>) value);
        } else if (value instanceof Collection) {
            appendCollection(sb, (Collection<?>) value);
        } else {
            throw new IllegalArgumentException("not serialisable: " + value.getClass().getName());
        }
    }

    private static void appendDouble(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            sb.append("null");
        } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
        } else {
            sb.append(d);
        }
    }

    private static void appendMap(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            appendString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            append(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void appendCollection(StringBuilder sb, Collection<?> items) {
        sb.append('[');
        boolean first = true;
        for (Object o : items) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            append(sb, o);
        }
        sb.append(']');
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
