package fr.capture.agent.runtime;

import java.util.Locale;

/** Cheap first-keyword classification of a SQL string (best effort, no parsing). */
public final class SqlKind {

    private SqlKind() {}

    public static String of(String sql) {
        if (sql == null) return "other";
        String s = stripLeading(sql);
        String w = firstWord(s).toLowerCase(Locale.ROOT);
        switch (w) {
            case "select": case "with":   return "select";
            case "insert":                return "insert";
            case "update":                return "update";
            case "delete":                return "delete";
            case "call":                  return "call";
            case "create": case "alter": case "drop": case "truncate": return "ddl";
            default:
                // {call ...} escape syntax
                if (s.startsWith("{") && s.toLowerCase(Locale.ROOT).contains("call")) return "call";
                return "other";
        }
    }

    public static boolean isWrite(String kind) {
        return "insert".equals(kind) || "update".equals(kind)
                || "delete".equals(kind) || "ddl".equals(kind) || "call".equals(kind);
    }

    private static String stripLeading(String sql) {
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {   // line comment
                int nl = sql.indexOf('\n', i);
                if (nl < 0) return "";
                i = nl + 1; continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {   // block comment
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) return "";
                i = end + 2; continue;
            }
            break;
        }
        return sql.substring(i);
    }

    private static String firstWord(String s) {
        int i = 0, n = s.length();
        while (i < n && (Character.isLetter(s.charAt(i)))) i++;
        return s.substring(0, i);
    }
}
