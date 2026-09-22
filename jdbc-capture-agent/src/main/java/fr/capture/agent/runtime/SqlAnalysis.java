package fr.capture.agent.runtime;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Best-effort SQL analysis, backed by the (relocated) JSqlParser.
 * <p>
 * Produces the set of tables read and written by a statement, and a normalised form of the query
 * (literals and parameters collapsed to {@code ?}) used to build the distinct-query list.
 * <p>
 * <b>Limits (documented, best effort):</b> JSqlParser does not understand every vendor dialect.
 * When parsing fails the {@link Touched#parsed} flag is {@code false}, the raw SQL is kept, and the
 * table classification is left empty rather than guessed. Normalisation is a lexical pass and can
 * mis-handle exotic literals; it never throws.
 */
public final class SqlAnalysis {

    private SqlAnalysis() {}

    public static final class Touched {
        public final Set<String> read = new LinkedHashSet<String>();
        public final Set<String> write = new LinkedHashSet<String>();
        public boolean parsed = false;
    }

    public static Touched touched(String sql) {
        Touched t = new Touched();
        if (sql == null || sql.trim().isEmpty()) return t;
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            List<String> all = new TablesNamesFinder().getTableList(stmt);
            Set<String> allNorm = new LinkedHashSet<String>();
            if (all != null) for (String n : all) allNorm.add(normName(n));

            if (stmt instanceof Select) {
                t.read.addAll(allNorm);
            } else if (stmt instanceof Insert) {
                String w = normName(((Insert) stmt).getTable().getName());
                t.write.add(w);
                for (String n : allNorm) if (!n.equals(w)) t.read.add(n);   // INSERT ... SELECT
            } else if (stmt instanceof Update) {
                String w = normName(((Update) stmt).getTable().getName());
                t.write.add(w);
                for (String n : allNorm) if (!n.equals(w)) t.read.add(n);
            } else if (stmt instanceof Delete) {
                String w = normName(((Delete) stmt).getTable().getName());
                t.write.add(w);
                for (String n : allNorm) if (!n.equals(w)) t.read.add(n);
            } else {
                // DDL / MERGE / CALL / unknown: do not over-claim, treat referenced tables as written
                t.write.addAll(allNorm);
            }
            t.parsed = true;
        } catch (Throwable ignored) {
            // parse failed: leave parsed=false, caller keeps the raw SQL and marks it "unparsed"
        }
        return t;
    }

    private static String normName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // strip quoting characters and schema/catalog prefix, lower-case for stable comparison
        s = s.replace("\"", "").replace("`", "").replace("[", "").replace("]", "");
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1) s = s.substring(dot + 1);
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * Lexical normalisation: replace string literals and numeric literals with {@code ?},
     * collapse whitespace. Robust to unparseable SQL (never throws).
     */
    public static String normalize(String sql) {
        if (sql == null) return null;
        StringBuilder out = new StringBuilder(sql.length());
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (c == '\'') {                       // single-quoted string literal
                out.append('?');
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') { i++; }   // escaped ''
                        else break;
                    }
                    i++;
                }
            } else if (Character.isDigit(c) && !isIdentifierChar(prev(sql, i))) {
                out.append('?');
                while (i + 1 < n && (Character.isDigit(sql.charAt(i + 1)) || sql.charAt(i + 1) == '.')) i++;
            } else if (Character.isWhitespace(c)) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    private static char prev(String s, int i) {
        return i > 0 ? s.charAt(i - 1) : ' ';
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
