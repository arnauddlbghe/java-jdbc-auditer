package fr.capture.agent;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Agent options, parsed from the {@code -javaagent:...=<here>} string.
 * <p>
 * Top-level options are separated by {@code ,}. Because a comma is the top separator,
 * list-valued options ({@code mask}) use {@code |} internally and {@code exclude} takes a
 * single regex token that must not contain a comma (documented limitation).
 * <p>
 * Parsing is fail-safe: an unknown or malformed option is logged to {@code agent-errors.log}
 * and the default is used. The agent never refuses to start because of a bad option.
 */
public final class Options {

    public enum RowsMode { NONE, SAMPLE, ALL }

    public Path outDir = Paths.get("./capture");
    public RowsMode rowsMode = RowsMode.SAMPLE;
    public int rowsSample = 100;
    public Set<String> maskColumns = Collections.emptySet();
    public String clock = null;           // ISO-8601 instant, or null (real clock)
    public Pattern excludePattern = null; // SQL regex to skip, or null
    public int queueCapacity = 65536;

    /**
     * Non-fatal parse warnings. Collected here (not logged directly) because option parsing runs
     * before the error log knows its output directory — {@code out} is itself an option. AgentMain
     * flushes these to {@code agent-errors.log} once the log is initialised.
     */
    public final List<String> warnings = new ArrayList<String>();

    public static Options parse(String raw) {
        Options o = new Options();
        if (raw == null || raw.trim().isEmpty()) return o;
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            String key = (eq < 0 ? p : p.substring(0, eq)).trim().toLowerCase(Locale.ROOT);
            String val = (eq < 0 ? "" : p.substring(eq + 1)).trim();
            try {
                apply(o, key, val);
            } catch (Throwable t) {
                o.warnings.add("Ignoring invalid agent option '" + p + "': " + t);
            }
        }
        return o;
    }

    private static void apply(Options o, String key, String val) {
        switch (key) {
            case "out":
                if (!val.isEmpty()) o.outDir = Paths.get(val);
                break;
            case "rows":
                parseRows(o, val);
                break;
            case "mask":
                o.maskColumns = parseList(val);
                break;
            case "clock":
                o.clock = val.isEmpty() ? null : val;
                break;
            case "exclude":
                o.excludePattern = val.isEmpty() ? null : Pattern.compile(val, Pattern.CASE_INSENSITIVE);
                break;
            case "queue":
                o.queueCapacity = Integer.parseInt(val);
                break;
            default:
                o.warnings.add("Unknown agent option '" + key + "' (ignored)");
        }
    }

    private static void parseRows(Options o, String val) {
        String v = val.toLowerCase(Locale.ROOT);
        if (v.equals("none")) {
            o.rowsMode = RowsMode.NONE;
        } else if (v.equals("all")) {
            o.rowsMode = RowsMode.ALL;
        } else if (v.startsWith("sample:")) {
            o.rowsMode = RowsMode.SAMPLE;
            o.rowsSample = Integer.parseInt(v.substring("sample:".length()));
        } else {
            throw new IllegalArgumentException("rows must be none|sample:N|all, got '" + val + "'");
        }
    }

    private static Set<String> parseList(String val) {
        Set<String> set = new HashSet<String>();
        for (String s : val.split("\\|")) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) set.add(t);
        }
        return set;
    }

    @Override
    public String toString() {
        return "Options{out=" + outDir + ", rows=" + rowsMode + (rowsMode == RowsMode.SAMPLE ? ":" + rowsSample : "")
                + ", mask=" + maskColumns + ", clock=" + clock
                + ", exclude=" + (excludePattern == null ? "none" : excludePattern.pattern())
                + ", queue=" + queueCapacity + "}";
    }
}
