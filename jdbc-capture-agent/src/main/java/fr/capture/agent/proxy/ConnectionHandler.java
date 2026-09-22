package fr.capture.agent.proxy;

import fr.capture.agent.runtime.Capture;
import fr.capture.agent.trace.Json;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dynamic-proxy handler for a captured {@link Connection}. Delegates every call to the real
 * connection, wraps returned {@link Statement}/{@link PreparedStatement}/{@link CallableStatement}
 * so we can observe queries, and records transaction lifecycle events.
 */
public final class ConnectionHandler implements InvocationHandler {

    private final Capture cap;
    private final Connection target;
    private final long connId;

    /** Logical transaction id; bumped on each commit/rollback so writes group per-transaction. */
    private final AtomicLong txSeq = new AtomicLong(1);
    private volatile boolean autoCommit = true;

    public ConnectionHandler(Capture cap, Connection target, long connId) {
        this.cap = cap;
        this.target = target;
        this.connId = connId;
    }

    public long connId() { return connId; }
    public long currentTx() { return txSeq.get(); }

    /** Raw underlying connection, used internally (under the re-entrancy guard) for PK metadata. */
    public Connection rawConnection() { return target; }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();

        // java.lang.Object methods: keep proxy identity sane.
        if (method.getDeclaringClass() == Object.class) {
            if ("equals".equals(name)) return proxy == args[0];
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("toString".equals(name)) return "CaptureConnection[" + connId + "]->" + safeToString();
        }

        Object result = invokeTarget(method, args);

        try {
            if (result instanceof CallableStatement) {
                return wrapStatement((Statement) result, arg0(args));
            }
            if (result instanceof PreparedStatement) {
                return wrapStatement((Statement) result, arg0(args));
            }
            if (result instanceof Statement) {
                return wrapStatement((Statement) result, null);
            }
            recordTx(name, args);
        } catch (Throwable t) {
            fr.capture.agent.ErrorLog.error("ConnectionHandler post-processing failed for " + name, t);
        }
        return result;
    }

    private Object wrapStatement(Statement stmt, String preparedSql) {
        try {
            StatementHandler h = new StatementHandler(cap, stmt, this, preparedSql);
            return Proxy.newProxyInstance(loader(stmt), Capture.interfacesOf(stmt), h);
        } catch (Throwable t) {
            fr.capture.agent.ErrorLog.error("wrapStatement failed (returning raw statement)", t);
            return stmt;
        }
    }

    private void recordTx(String name, Object[] args) {
        if ("commit".equals(name)) {
            emitTx("commit");
            txSeq.incrementAndGet();
        } else if ("rollback".equals(name)) {
            emitTx("rollback");
            txSeq.incrementAndGet();
        } else if ("setAutoCommit".equals(name) && args != null && args.length == 1) {
            boolean now = Boolean.TRUE.equals(args[0]);
            if (now != autoCommit) {
                cap.emit(cap.event("tx", connId, txSeq.get()).add("kind", "autocommit").add("value", now));
                autoCommit = now;
            }
        } else if ("close".equals(name)) {
            // A close with an open manual transaction is an implicit rollback of uncommitted work.
            if (!autoCommit) {
                emitTx("rollback_implicit");
                txSeq.incrementAndGet();
            }
            cap.emit(cap.event("conn_close", connId, txSeq.get()));
        }
    }

    private void emitTx(String kind) {
        Json e = cap.event("tx", connId, txSeq.get()).add("kind", kind);
        cap.emit(e);
    }

    private Object invokeTarget(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    private static String arg0(Object[] args) {
        return (args != null && args.length > 0 && args[0] instanceof String) ? (String) args[0] : null;
    }

    private static ClassLoader loader(Object o) {
        ClassLoader cl = o.getClass().getClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
    }

    private String safeToString() {
        try { return target.toString(); } catch (Throwable t) { return "?"; }
    }
}
