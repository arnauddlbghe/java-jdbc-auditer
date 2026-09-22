package fr.capture.agent.proxy;

import fr.capture.agent.ErrorLog;
import fr.capture.agent.runtime.Capture;
import fr.capture.agent.runtime.Reentrancy;
import fr.capture.agent.runtime.SqlAnalysis;
import fr.capture.agent.runtime.SqlKind;
import fr.capture.agent.trace.Json;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Proxy handler for {@link Statement}/{@link PreparedStatement}/{@link CallableStatement}.
 * <p>Observes:
 * <ul>
 *   <li>bound parameters (typed) set via {@code setXxx} on Prepared/Callable statements,</li>
 *   <li>batches ({@code addBatch}/{@code clearBatch}/{@code executeBatch}),</li>
 *   <li>{@code execute*} calls: SQL, kind, duration, affected rows,</li>
 *   <li>generated keys and result sets (wrapped for row capture),</li>
 *   <li>a {@code touched} event (tables read/written) per executed statement.</li>
 * </ul>
 * Everything is guarded; a failure never propagates to the application.
 */
public final class StatementHandler implements InvocationHandler {

    private static final class Param {
        final Object key;     // Integer index or String name
        final String type;    // SQL setter suffix, e.g. INT, STRING, NULL, OBJECT
        final Object value;
        Param(Object key, String type, Object value) { this.key = key; this.type = type; this.value = value; }
    }

    private final Capture cap;
    private final Statement target;
    private final ConnectionHandler conn;
    private final String preparedSql;   // null for plain Statement

    private final Map<Object, Param> params = new LinkedHashMap<Object, Param>();
    private final List<Map<Object, Param>> paramBatches = new ArrayList<Map<Object, Param>>();
    private final List<String> sqlBatches = new ArrayList<String>();

    public StatementHandler(Capture cap, Statement target, ConnectionHandler conn, String preparedSql) {
        this.cap = cap;
        this.target = target;
        this.conn = conn;
        this.preparedSql = preparedSql;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();

        if (method.getDeclaringClass() == Object.class) {
            if ("equals".equals(name)) return proxy == args[0];
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("toString".equals(name)) return "CaptureStatement->" + safe();
        }

        // Parameter bindings and batch bookkeeping happen before the call.
        try {
            preInvoke(method, name, args);
        } catch (Throwable t) {
            ErrorLog.error("StatementHandler pre-processing failed for " + name, t);
        }

        boolean isExecute = name.startsWith("execute");
        long startNanos = isExecute ? System.nanoTime() : 0L;
        Object result = invokeTarget(method, args);

        try {
            if ("executeBatch".equals(name) || "executeLargeBatch".equals(name)) {
                recordBatch(result, System.nanoTime() - startNanos);
            } else if (isExecute) {
                recordSingle(name, args, result, System.nanoTime() - startNanos);
            }

            if ("getGeneratedKeys".equals(name) && result instanceof ResultSet) {
                return wrapResultSet((ResultSet) result, effectiveSql(name, args), "generated_keys");
            }
            if (result instanceof ResultSet) {
                String sql = effectiveSql(name, args);
                if (cap.isExcluded(sql)) return result;   // excluded: do not capture rows
                return wrapResultSet((ResultSet) result, sql, "result");
            }
        } catch (Throwable t) {
            ErrorLog.error("StatementHandler post-processing failed for " + name, t);
        }
        return result;
    }

    // ---- parameter / batch bookkeeping -----------------------------------------------------

    private void preInvoke(Method method, String name, Object[] args) {
        Class<?> decl = method.getDeclaringClass();
        boolean paramSetter = (decl == PreparedStatement.class || decl == CallableStatement.class)
                && name.startsWith("set") && args != null && args.length >= 1
                && (args[0] instanceof Integer || args[0] instanceof String);

        if (paramSetter) {
            Object key = args[0];
            String type = name.substring(3).toUpperCase(Locale.ROOT);
            Object value = "setNull".equals(name) ? null : (args.length >= 2 ? args[1] : null);
            params.put(key, new Param(key, type, value));
            return;
        }
        if ("clearParameters".equals(name)) {
            params.clear();
        } else if ("addBatch".equals(name)) {
            if (preparedSql != null && (args == null || args.length == 0)) {
                paramBatches.add(new LinkedHashMap<Object, Param>(params));      // snapshot
            } else if (args != null && args.length == 1 && args[0] instanceof String) {
                sqlBatches.add((String) args[0]);                                // plain Statement batch
            }
        } else if ("clearBatch".equals(name)) {
            paramBatches.clear();
            sqlBatches.clear();
        }
    }

    // ---- execution recording ---------------------------------------------------------------

    private void recordSingle(String name, Object[] args, Object result, long durNanos) {
        String sql = effectiveSql(name, args);
        if (cap.isExcluded(sql)) { cap.incExcluded(); return; }

        String kind = SqlKind.of(sql);
        Long affected = asUpdateCount(result);
        if (affected == null && result instanceof Boolean && !((Boolean) result)) {
            affected = updateCountAfterExecute();   // execute() returned false → update count available
        }

        Json e = cap.event("query", conn.connId(), conn.currentTx())
                .add("sql", sql)
                .add("kind", kind)
                .add("duration_ms", durNanos / 1_000_000L);
        if (!params.isEmpty()) e.addRaw("params", paramsJson(params));
        if (affected != null) e.add("affected", affected);
        cap.emit(e);

        cap.incQueries();
        if (SqlKind.isWrite(kind)) cap.incWrites();
        emitTouched(sql, kind, affected);
    }

    private void recordBatch(Object result, long durNanos) {
        boolean prepared = preparedSql != null;
        String sql = prepared ? preparedSql : firstOrNull(sqlBatches);
        if (cap.isExcluded(sql)) { cap.incExcluded(); afterBatch(); return; }

        long[] counts = toCounts(result);
        long totalAffected = 0;
        for (long c : counts) if (c > 0) totalAffected += c;
        String kind = SqlKind.of(sql);

        Json e = cap.event("query", conn.connId(), conn.currentTx())
                .add("sql", sql)
                .add("kind", kind)
                .add("batch", true)
                .add("batch_size", prepared ? paramBatches.size() : sqlBatches.size())
                .add("duration_ms", durNanos / 1_000_000L)
                .addRaw("update_counts", longArray(counts));
        if (prepared) {
            e.addRaw("params_batch", paramBatchesJson());
        } else {
            e.addRaw("sql_batch", stringArrayJson(sqlBatches));
        }
        if (totalAffected > 0) e.add("affected", totalAffected);
        cap.emit(e);

        cap.incQueries();
        if (SqlKind.isWrite(kind)) cap.incWrites();
        if (prepared) {
            emitTouched(sql, kind, totalAffected);
        } else {
            for (String s : sqlBatches) emitTouched(s, SqlKind.of(s), 0L);
        }
        afterBatch();
    }

    private void afterBatch() {
        paramBatches.clear();
        sqlBatches.clear();
    }

    /** Emit the touched-tables event and feed the run summary. */
    private void emitTouched(String sql, String kind, Long affected) {
        if (sql == null || cap.isExcluded(sql)) return;
        SqlAnalysis.Touched t = SqlAnalysis.touched(sql);

        Json e = cap.event("touched", conn.connId(), conn.currentTx())
                .add("kind", kind)
                .addRaw("read", stringArrayJson(t.read))
                .addRaw("write", stringArrayJson(t.write))
                .add("parsed", t.parsed);
        if (!t.parsed) e.add("unparsed", true).add("sql_raw", sql);
        cap.emit(e);

        cap.summary().addDistinctQuery(SqlAnalysis.normalize(sql));
        long a = affected != null && affected > 0 ? affected : 0;
        for (String w : t.write) cap.summary().addWrite(w, a);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private Long updateCountAfterExecute() {
        Reentrancy.enter();
        try {
            int uc = target.getUpdateCount();
            return uc >= 0 ? Long.valueOf(uc) : null;
        } catch (Throwable t) {
            return null;
        } finally {
            Reentrancy.exit();
        }
    }

    private String effectiveSql(String name, Object[] args) {
        if (preparedSql != null) return preparedSql;
        if (args != null && args.length > 0 && args[0] instanceof String) return (String) args[0];
        return null;
    }

    private static Long asUpdateCount(Object result) {
        if (result instanceof Integer) return ((Integer) result).longValue();
        if (result instanceof Long) return (Long) result;
        return null;
    }

    private static long[] toCounts(Object result) {
        if (result instanceof int[]) {
            int[] a = (int[]) result;
            long[] out = new long[a.length];
            for (int i = 0; i < a.length; i++) out[i] = a[i];
            return out;
        }
        if (result instanceof long[]) return (long[]) result;
        return new long[0];
    }

    private String paramsJson(Map<Object, Param> map) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Param p : map.values()) {
            if (!first) sb.append(',');
            first = false;
            Json o = Json.obj();
            if (p.key instanceof Integer) o.add("idx", ((Integer) p.key).longValue());
            else o.add("name", String.valueOf(p.key));
            o.add("type", p.type);
            // Bound parameters are not masked (no reliable column mapping); documented limitation.
            o.addRaw("value", ValueFormat.valueJson(p.value, false));
            sb.append(o.end());
        }
        return sb.append(']').toString();
    }

    private String paramBatchesJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < paramBatches.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(paramsJson(paramBatches.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String longArray(long[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.append(']').toString();
    }

    private static String stringArrayJson(Iterable<String> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String v : values) {
            if (!first) sb.append(',');
            first = false;
            sb.append(Json.quote(v));
        }
        return sb.append(']').toString();
    }

    private static String firstOrNull(List<String> l) {
        return l.isEmpty() ? null : l.get(0);
    }

    private Object wrapResultSet(ResultSet rs, String sql, String role) {
        try {
            ResultSetHandler h = new ResultSetHandler(cap, rs, conn, sql, role);
            return Proxy.newProxyInstance(loader(rs), Capture.interfacesOf(rs), h);
        } catch (Throwable t) {
            ErrorLog.error("wrapResultSet failed (returning raw result set)", t);
            return rs;
        }
    }

    private Object invokeTarget(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    private static ClassLoader loader(Object o) {
        ClassLoader cl = o.getClass().getClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
    }

    private String safe() {
        try { return target.toString(); } catch (Throwable t) { return "?"; }
    }
}
