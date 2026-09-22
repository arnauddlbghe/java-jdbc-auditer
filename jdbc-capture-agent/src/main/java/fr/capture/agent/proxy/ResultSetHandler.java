package fr.capture.agent.proxy;

import fr.capture.agent.ErrorLog;
import fr.capture.agent.Options;
import fr.capture.agent.runtime.Capture;
import fr.capture.agent.runtime.SqlAnalysis;
import fr.capture.agent.trace.Json;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Proxy handler for a {@link ResultSet}. Counts rows via {@code next()}, captures values according
 * to the {@code rows=none|sample:N|all} option (LOBs as length+SHA-256, masked columns hashed), and
 * extracts primary-key values when the PK columns are present in the result set. Emits a
 * {@code rows_read} event (or {@code generated_keys} for a generated-keys result set).
 */
public final class ResultSetHandler implements InvocationHandler {

    private final Capture cap;
    private final ResultSet target;
    private final ConnectionHandler conn;
    private final String sql;
    private final String role;   // "result" or "generated_keys"

    private long rowCount = 0;
    private boolean emitted = false;
    private boolean inited = false;

    private String[] colLabels;      // 1-based-1 (index 0 unused conceptually; we keep 0-based)
    private String[] colTypes;
    private String columnsJson = "[]";

    private String pkTable;                 // single read table, if any
    private List<String> pkColumns;         // PK column names present in this result set (else null)
    private final List<String> rowsJson = new ArrayList<String>();
    private final List<String> pkTuples = new ArrayList<String>();
    private int capturedRows = 0;

    public ResultSetHandler(Capture cap, ResultSet target, ConnectionHandler conn, String sql, String role) {
        this.cap = cap;
        this.target = target;
        this.conn = conn;
        this.sql = sql;
        this.role = role;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();

        if (method.getDeclaringClass() == Object.class) {
            if ("equals".equals(name)) return proxy == args[0];
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("toString".equals(name)) return "CaptureResultSet->" + safe();
        }

        boolean isNext = "next".equals(name);
        if (isNext && !inited) initOnce();     // metadata + PK setup while the RS is open

        Object result = invokeTarget(method, args);

        try {
            if (isNext) {
                if (Boolean.TRUE.equals(result)) {
                    rowCount++;
                    captureRow();
                } else {
                    emitRowsRead();            // end of set reached (fallback if close() is skipped)
                }
            } else if ("close".equals(name)) {
                emitRowsRead();
            }
        } catch (Throwable t) {
            ErrorLog.error("ResultSetHandler post-processing failed for " + name, t);
        }
        return result;
    }

    private void initOnce() {
        inited = true;
        try {
            ResultSetMetaData md = target.getMetaData();
            int n = md.getColumnCount();
            colLabels = new String[n];
            colTypes = new String[n];
            StringBuilder sb = new StringBuilder("[");
            for (int i = 1; i <= n; i++) {
                colLabels[i - 1] = md.getColumnLabel(i);
                colTypes[i - 1] = md.getColumnTypeName(i);
                if (i > 1) sb.append(',');
                sb.append(Json.obj().add("name", colLabels[i - 1]).add("type", colTypes[i - 1]).end());
            }
            columnsJson = sb.append(']').toString();
        } catch (Throwable t) {
            ErrorLog.error("ResultSet metadata read failed", t);
        }
        setupPrimaryKey();
    }

    /** If the query reads exactly one table and its PK columns are all present, prepare extraction. */
    private void setupPrimaryKey() {
        try {
            if ("generated_keys".equals(role) || colLabels == null) return;
            SqlAnalysis.Touched t = SqlAnalysis.touched(sql);
            if (t.read.size() != 1) return;
            String table = t.read.iterator().next();
            List<String> pk = cap.pkColumns(conn.rawConnection(), table);
            if (pk.isEmpty()) return;

            Map<String, Integer> labelIdx = new HashMap<String, Integer>();
            for (int i = 0; i < colLabels.length; i++) labelIdx.put(colLabels[i].toLowerCase(Locale.ROOT), i);
            List<String> present = new ArrayList<String>();
            for (String c : pk) {
                if (!labelIdx.containsKey(c.toLowerCase(Locale.ROOT))) return;   // PK not fully present
                present.add(c);
            }
            this.pkTable = table;
            this.pkColumns = present;
        } catch (Throwable t) {
            ErrorLog.error("setupPrimaryKey failed", t);
        }
    }

    private int captureLimit() {
        if ("generated_keys".equals(role)) return Integer.MAX_VALUE;   // keys are always fully captured
        Options o = cap.options();
        switch (o.rowsMode) {
            case NONE:   return 0;
            case ALL:    return Integer.MAX_VALUE;
            case SAMPLE: return o.rowsSample;
            default:     return 0;
        }
    }

    private void captureRow() {
        try {
            if (colLabels == null) return;
            if (capturedRows < captureLimit()) {
                rowsJson.add(rowValuesJson());
                capturedRows++;
            }
            if (pkColumns != null) {
                String tuple = pkTuple();
                if (tuple != null) { pkTuples.add(tuple); cap.summary().addPk(pkTable, tuple); }
            }
        } catch (Throwable t) {
            ErrorLog.error("captureRow failed", t);
        }
    }

    private String rowValuesJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < colLabels.length; i++) {
            if (i > 0) sb.append(',');
            Object v;
            try { v = target.getObject(i + 1); } catch (Throwable t) { v = null; }
            boolean masked = cap.isMaskedColumn(colLabels[i]);
            sb.append(ValueFormat.valueJson(v, masked));
        }
        return sb.append(']').toString();
    }

    private String pkTuple() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pkColumns.size(); i++) {
            String col = pkColumns.get(i);
            Object v;
            try { v = target.getObject(col); } catch (Throwable t) { return null; }
            if (i > 0) sb.append(',');
            sb.append(col).append('=');
            sb.append(cap.isMaskedColumn(col) ? fr.capture.agent.runtime.Hashing.mask(String.valueOf(v))
                                              : String.valueOf(v));
        }
        return sb.toString();
    }

    private void emitRowsRead() {
        if (emitted) return;
        emitted = true;
        try {
            if (cap.isExcluded(sql)) return;
            String type = "generated_keys".equals(role) ? "generated_keys" : "rows_read";
            Json e = cap.event(type, conn.connId(), conn.currentTx())
                    .add("sql", sql)
                    .add("rows", rowCount)
                    .add("values_mode", "generated_keys".equals(role) ? "all" : cap.options().rowsMode.name().toLowerCase(Locale.ROOT))
                    .add("values_captured", capturedRows)
                    .addRaw("columns", columnsJson)
                    .addRaw("values", arrayOf(rowsJson));
            if (pkColumns != null) {
                e.add("pk_table", pkTable)
                 .addRaw("pk_columns", stringArray(pkColumns))
                 .addRaw("primary_keys", stringArray(pkTuples));
            }
            cap.emit(e);

            cap.addRowsRead(rowCount);
            if (!"generated_keys".equals(role)) {
                SqlAnalysis.Touched t = SqlAnalysis.touched(sql);
                for (String r : t.read) cap.summary().addRead(r, rowCount);
            }
        } catch (Throwable t) {
            ErrorLog.error("emitRowsRead failed", t);
        }
    }

    private static String arrayOf(List<String> rawJsonElements) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rawJsonElements.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(rawJsonElements.get(i));
        }
        return sb.append(']').toString();
    }

    private static String stringArray(Iterable<String> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String v : values) {
            if (!first) sb.append(',');
            first = false;
            sb.append(Json.quote(v));
        }
        return sb.append(']').toString();
    }

    private Object invokeTarget(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    private String safe() {
        try { return target.toString(); } catch (Throwable t) { return "?"; }
    }
}
