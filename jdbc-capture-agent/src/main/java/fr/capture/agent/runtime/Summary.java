package fr.capture.agent.runtime;

import fr.capture.agent.ErrorLog;
import fr.capture.agent.trace.Json;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Accumulates the end-of-run {@code summary.json}: tables read/written with counters, the set of
 * primary-key values read per table, and the list of distinct normalised queries. Fully thread-safe
 * (the trace is produced concurrently by all application threads).
 */
public final class Summary {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Map<String, AtomicLong> readTables = new ConcurrentHashMap<String, AtomicLong>();
    private final Map<String, AtomicLong> writeTables = new ConcurrentHashMap<String, AtomicLong>();
    private final Map<String, Set<String>> pkByTable = new ConcurrentHashMap<String, Set<String>>();
    private final Set<String> distinctQueries =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public void addRead(String table, long rows) {
        if (table == null || table.isEmpty()) return;
        counter(readTables, table).addAndGet(Math.max(rows, 0));
    }

    public void addWrite(String table, long affected) {
        if (table == null || table.isEmpty()) return;
        counter(writeTables, table).addAndGet(Math.max(affected, 0));
    }

    public void addPk(String table, String pkTuple) {
        if (table == null || table.isEmpty() || pkTuple == null) return;
        Set<String> set = pkByTable.get(table);
        if (set == null) {
            set = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
            Set<String> prev = pkByTable.putIfAbsent(table, set);
            if (prev != null) set = prev;
        }
        set.add(pkTuple);
    }

    public void addDistinctQuery(String normalized) {
        if (normalized != null && !normalized.isEmpty()) distinctQueries.add(normalized);
    }

    private static AtomicLong counter(Map<String, AtomicLong> map, String key) {
        AtomicLong a = map.get(key);
        if (a == null) {
            a = new AtomicLong();
            AtomicLong prev = map.putIfAbsent(key, a);
            if (prev != null) a = prev;
        }
        return a;
    }

    public void write(Path file, Json runHeader) {
        try {
            Files.createDirectories(file.getParent());
            String json = build(runHeader);
            Files.write(file, json.getBytes(UTF8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Throwable t) {
            ErrorLog.error("Summary.write failed for " + file, t);
        }
    }

    private String build(Json runHeader) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append('{');
        sb.append("\"run\":").append(runHeader.end());
        sb.append(",\"tables_read\":").append(tableCounts(readTables));
        sb.append(",\"tables_written\":").append(tableCounts(writeTables));
        sb.append(",\"primary_keys_read\":").append(pkJson());
        sb.append(",\"distinct_queries\":").append(stringArray(new TreeSet<String>(distinctQueries)));
        sb.append('}');
        return sb.toString();
    }

    private String tableCounts(Map<String, AtomicLong> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, AtomicLong> e : new TreeMap<String, AtomicLong>(map).entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(Json.quote(e.getKey())).append(':').append(e.getValue().get());
        }
        return sb.append('}').toString();
    }

    private String pkJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Set<String>> e : new TreeMap<String, Set<String>>(pkByTable).entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(Json.quote(e.getKey())).append(':').append(stringArray(new TreeSet<String>(e.getValue())));
        }
        return sb.append('}').toString();
    }

    private String stringArray(Iterable<String> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String v : values) {
            if (!first) sb.append(',');
            first = false;
            sb.append(Json.quote(v));
        }
        return sb.append(']').toString();
    }
}
