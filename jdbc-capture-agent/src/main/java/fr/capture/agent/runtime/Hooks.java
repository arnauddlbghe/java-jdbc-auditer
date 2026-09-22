package fr.capture.agent.runtime;

import fr.capture.agent.ErrorLog;

import java.sql.Connection;

/**
 * Static facade called from the inlined Byte Buddy advice. Keeping the advice body a single
 * static call keeps the code injected into driver/pool classes trivial and fully guarded.
 */
public final class Hooks {

    private Hooks() {}

    /**
     * Invoked on the return of {@code Driver#connect} / {@code DataSource#getConnection}.
     * Returns the (possibly wrapped) connection. Must never throw.
     */
    public static Connection onConnection(Connection returned, String source) {
        try {
            Capture c = Capture.get();
            if (c == null) return returned;      // agent not fully installed: pass through
            if (Reentrancy.active()) return returned; // nested inside our own logic: don't wrap
            return c.wrapConnection(returned, source);
        } catch (Throwable t) {
            ErrorLog.error("Hooks.onConnection failed", t);
            return returned;                     // fail-safe
        }
    }
}
