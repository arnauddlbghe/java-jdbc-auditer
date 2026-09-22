package fr.capture.agent.trace;

/**
 * Minimal, dependency-free JSON writer. We deliberately avoid a JSON library on the
 * hot path: it keeps the shaded jar small and the agent's behaviour trivially auditable.
 * Only what the trace needs is implemented (objects, arrays, strings, numbers, booleans, null).
 */
public final class Json {

    private final StringBuilder sb = new StringBuilder(256);
    private boolean first = true;

    public static Json obj() {
        Json j = new Json();
        j.sb.append('{');
        return j;
    }

    private void comma() {
        if (!first) sb.append(',');
        first = false;
    }

    private void key(String k) {
        comma();
        str(k);
        sb.append(':');
    }

    public Json add(String k, String v) {
        key(k);
        if (v == null) sb.append("null"); else str(v);
        return this;
    }

    public Json add(String k, long v) {
        key(k);
        sb.append(v);
        return this;
    }

    public Json add(String k, Long v) {
        key(k);
        if (v == null) sb.append("null"); else sb.append(v.longValue());
        return this;
    }

    public Json add(String k, boolean v) {
        key(k);
        sb.append(v);
        return this;
    }

    /** Raw pre-serialised JSON fragment (already a valid value, e.g. an array/object). */
    public Json addRaw(String k, String rawJson) {
        key(k);
        sb.append(rawJson == null ? "null" : rawJson);
        return this;
    }

    public String end() {
        sb.append('}');
        return sb.toString();
    }

    private void str(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
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

    /** Escape a bare string into a quoted JSON string (utility for building raw fragments). */
    public static String quote(String s) {
        if (s == null) return "null";
        Json j = new Json();
        j.str(s);
        return j.sb.toString();
    }
}
