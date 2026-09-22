package fr.capture.agent.runtime;

/**
 * Per-thread re-entrancy guard.
 * <p>
 * Two uses:
 * <ul>
 *   <li>Prevent the agent's own JDBC calls (e.g. reading {@code DatabaseMetaData} for primary
 *       keys) from being captured again if they happen to flow back through a wrapped object.</li>
 *   <li>Let the connection-wrapping logic know it is nested inside another wrap/getConnection on
 *       the same thread (HikariCP delegating to the driver), so it does not double-wrap.</li>
 * </ul>
 */
public final class Reentrancy {

    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<int[]>() {
        @Override protected int[] initialValue() { return new int[1]; }
    };

    private Reentrancy() {}

    public static boolean active() {
        return DEPTH.get()[0] > 0;
    }

    public static void enter() {
        DEPTH.get()[0]++;
    }

    public static void exit() {
        int[] d = DEPTH.get();
        if (d[0] > 0) d[0]--;
    }
}
