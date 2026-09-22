package fr.capture.agent.runtime;

import fr.capture.agent.ErrorLog;
import fr.capture.agent.Options;
import fr.capture.agent.proxy.ConnectionHandler;
import fr.capture.agent.trace.Json;
import fr.capture.agent.trace.TraceWriter;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central runtime context of the agent: owns the options, the trace writer, the run identity,
 * the shared counters, the primary-key cache and the end-of-run summary, and is the single place
 * that decides whether/how to wrap a {@link Connection}. One instance per JVM.
 */
public final class Capture {

    private static volatile Capture INSTANCE;

    private final Options opts;
    private final TraceWriter writer;
    private final String runId;
    private final Summary summary = new Summary();

    // Frozen-clock support (agent event timestamps only — see now()).
    private final long clockAnchorMillis;   // -1 when no frozen clock
    private final long realAnchorMillis;
    private final String startIso;

    private final Map<String, List<String>> pkCache = new ConcurrentHashMap<String, List<String>>();

    private final AtomicLong connSeq = new AtomicLong();
    private final AtomicLong queryCount = new AtomicLong();
    private final AtomicLong rowsReadCount = new AtomicLong();
    private final AtomicLong writeCount = new AtomicLong();
    private final AtomicLong excludedCount = new AtomicLong();

    private Capture(Options opts, TraceWriter writer) {
        this.opts = opts;
        this.writer = writer;
        this.runId = UUID.randomUUID().toString();
        this.realAnchorMillis = System.currentTimeMillis();
        this.clockAnchorMillis = parseClock(opts.clock);
        this.startIso = now();
    }

    public static Capture get() { return INSTANCE; }

    public static void install(Options opts, TraceWriter writer) {
        INSTANCE = new Capture(opts, writer);
    }

    public Options options() { return opts; }
    public String runId() { return runId; }
    public Summary summary() { return summary; }
    public String startIso() { return startIso; }

    /**
     * Timestamp for agent events. With {@code clock=<ISO-8601>}, time is anchored at the given
     * instant and advances by the real elapsed wall-clock since install, so two runs align.
     * <p><b>Important limitation:</b> this only affects the timestamps the agent writes. It does
     * NOT change the clock the application observes (System.currentTimeMillis, new Date(),
     * LocalDateTime.now()), because doing so would require instrumenting bootstrap JDK classes.
     */
    public String now() {
        if (clockAnchorMillis < 0) return Instant.now().toString();
        long elapsed = System.currentTimeMillis() - realAnchorMillis;
        return Instant.ofEpochMilli(clockAnchorMillis + elapsed).toString();
    }

    private static long parseClock(String raw) {
        if (raw == null || raw.trim().isEmpty()) return -1;
        String s = raw.trim();
        try { return Instant.parse(s).toEpochMilli(); } catch (Throwable ignored) {}
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli(); } catch (Throwable ignored) {}
        try { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli(); } catch (Throwable ignored) {}
        ErrorLog.error("Invalid clock value '" + raw + "' (expected ISO-8601); frozen clock disabled");
        return -1;
    }

    public long nextConnectionId() { return connSeq.incrementAndGet(); }

    public void incQueries() { queryCount.incrementAndGet(); }
    public void addRowsRead(long n) { rowsReadCount.addAndGet(n); }
    public void incWrites() { writeCount.incrementAndGet(); }
    public void incExcluded() { excludedCount.incrementAndGet(); }

    public boolean isExcluded(String sql) {
        try {
            return sql != null && opts.excludePattern != null && opts.excludePattern.matcher(sql).find();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isMaskedColumn(String columnName) {
        return columnName != null && !opts.maskColumns.isEmpty()
                && opts.maskColumns.contains(columnName.toLowerCase(Locale.ROOT));
    }

    /** Base envelope common to every event: timestamp, run, thread, connection, tx. */
    public Json event(String type, long connId, long txId) {
        return Json.obj()
                .add("event", type)
                .add("ts", now())
                .add("run", runId)
                .add("thread", Thread.currentThread().getName())
                .add("conn", connId)
                .add("tx", txId);
    }

    public void emit(Json event) {
        try {
            writer.write(event.end());
        } catch (Throwable t) {
            ErrorLog.error("emit failed", t);
        }
    }

    /**
     * Primary-key column names for a table, cached (one {@code DatabaseMetaData#getPrimaryKeys}
     * lookup per table). The lookup runs under the re-entrancy guard so it is not itself captured.
     * Returns an empty list when the table has no PK or the lookup fails (also cached).
     */
    public List<String> pkColumns(Connection raw, String table) {
        if (raw == null || table == null || table.isEmpty()) return Collections.emptyList();
        String key = table.toLowerCase(Locale.ROOT);
        List<String> cached = pkCache.get(key);
        if (cached != null) return cached;

        List<String> pks = new ArrayList<String>();
        Reentrancy.enter();
        try {
            DatabaseMetaData md = raw.getMetaData();
            lookupPk(md, table, pks);
            if (pks.isEmpty()) lookupPk(md, table.toUpperCase(Locale.ROOT), pks);
            if (pks.isEmpty()) lookupPk(md, table.toLowerCase(Locale.ROOT), pks);
        } catch (Throwable t) {
            ErrorLog.error("pkColumns lookup failed for " + table, t);
        } finally {
            Reentrancy.exit();
        }
        List<String> immutable = Collections.unmodifiableList(pks);
        List<String> prev = pkCache.putIfAbsent(key, immutable);
        return prev != null ? prev : immutable;
    }

    private static void lookupPk(DatabaseMetaData md, String table, List<String> out) {
        if (!out.isEmpty()) return;
        ResultSet rs = null;
        try {
            rs = md.getPrimaryKeys(null, null, table);
            TreeMap<Short, String> bySeq = new TreeMap<Short, String>();
            while (rs.next()) {
                bySeq.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
            out.addAll(bySeq.values());
        } catch (Throwable ignored) {
            // best effort per case variant
        } finally {
            if (rs != null) try { rs.close(); } catch (Throwable ignored) {}
        }
    }

    // ---- connection wrapping (anti double-count) -------------------------------------------

    public Connection wrapConnection(Connection conn, String source) {
        if (conn == null) return null;
        try {
            if (isOurs(conn)) return conn;                 // already our proxy
            if (unwrapsToOurs(conn)) return conn;          // pool wrapper over our physical proxy
            long id = nextConnectionId();
            emit(event("conn_open", id, 0).add("source", source));
            ConnectionHandler h = new ConnectionHandler(this, conn, id);
            return (Connection) Proxy.newProxyInstance(loaderFor(conn), interfacesOf(conn), h);
        } catch (Throwable t) {
            ErrorLog.error("wrapConnection failed (returning original, uninstrumented)", t);
            return conn;   // fail-safe: never break the application
        }
    }

    public static boolean isOurs(Object o) {
        try {
            if (o != null && Proxy.isProxyClass(o.getClass())) {
                InvocationHandler h = Proxy.getInvocationHandler(o);
                return h instanceof ConnectionHandler;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean unwrapsToOurs(Connection conn) {
        try {
            if (conn.isWrapperFor(Connection.class)) {
                Connection inner = conn.unwrap(Connection.class);
                if (inner != conn && isOurs(inner)) return true;
            }
        } catch (Throwable ignored) {
            // unwrap is best-effort; if it fails we simply fall through and wrap normally
        }
        return false;
    }

    private static ClassLoader loaderFor(Object o) {
        ClassLoader cl = o.getClass().getClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
    }

    /** All interfaces implemented across the object's class hierarchy (so casts still work). */
    public static Class<?>[] interfacesOf(Object o) {
        Set<Class<?>> ifaces = new LinkedHashSet<Class<?>>();
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) collect(i, ifaces);
        }
        if (ifaces.isEmpty()) ifaces.add(Connection.class);
        return ifaces.toArray(new Class<?>[0]);
    }

    private static void collect(Class<?> i, Set<Class<?>> acc) {
        if (acc.add(i)) {
            for (Class<?> s : i.getInterfaces()) collect(s, acc);
        }
    }

    // ---- shutdown --------------------------------------------------------------------------

    public void shutdown(String[] mainArgs) {
        try {
            // summary.json first, so it exists even if run_end emission has trouble
            Json header = Json.obj()
                    .add("run", runId)
                    .add("java_version", System.getProperty("java.version"))
                    .add("started", startIso)
                    .add("ended", now())
                    .add("queries", queryCount.get())
                    .add("rows_read", rowsReadCount.get())
                    .add("writes", writeCount.get())
                    .add("excluded", excludedCount.get())
                    .add("dropped", writer.droppedCount());
            summary.write(opts.outDir.resolve("summary.json"), header);

            emit(event("run_end", 0, 0)
                    .add("queries", queryCount.get())
                    .add("rows_read", rowsReadCount.get())
                    .add("writes", writeCount.get())
                    .add("excluded", excludedCount.get())
                    .add("dropped", writer.droppedCount())
                    .add("trace_written", writer.writtenCount()));
        } catch (Throwable t) {
            ErrorLog.error("shutdown emit/summary failed", t);
        } finally {
            writer.shutdown();
        }
    }
}
