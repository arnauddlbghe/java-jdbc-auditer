package fr.capture.legacy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Legacy-style batch program: raw JDBC over {@link DriverManager}, no framework, Java 8.
 *
 * <p>It reproduces the shape of the batch we are migrating:</p>
 * <ul>
 *   <li>idempotent schema creation ({@code account}, {@code ledger_entry});</li>
 *   <li>a <b>committed</b> transaction that inserts accounts (via {@code addBatch}/{@code executeBatch})
 *       and a few ledger entries;</li>
 *   <li>a <b>deliberately rolled-back</b> transaction whose writes must therefore be observable in the
 *       capture trace but attached to a transaction that ends with {@code rollback} (i.e. not committed);</li>
 *   <li>a read-back {@code SELECT}.</li>
 * </ul>
 *
 * <p>Two scenarios via the first CLI argument:</p>
 * <ul>
 *   <li>{@code normal} (default): the full workload above;</li>
 *   <li>{@code empty}: "nothing to process" — only a {@code SELECT} that returns no rows, no writes.</li>
 * </ul>
 *
 * <p>DB configuration comes from environment variables {@code JDBC_URL}, {@code JDBC_USER},
 * {@code JDBC_PASSWORD} (with local defaults). SQL here is intentionally different from
 * {@code sample-modern} so the two traces can be compared meaningfully.</p>
 */
public final class LegacyApp {

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "normal";
        String url = env("JDBC_URL", "jdbc:postgresql://localhost:5432/capture");
        String user = env("JDBC_USER", "capture");
        String password = env("JDBC_PASSWORD", "capture");

        System.out.println("[legacy] scenario=" + scenario + " url=" + url);

        try (Connection c = DriverManager.getConnection(url, user, password)) {
            createSchema(c);
            if ("empty".equals(scenario)) {
                runEmpty(c);
            } else {
                runNormal(c);
            }
        }
        System.out.println("[legacy] done");
    }

    /** Idempotent schema. Executed in autocommit so it is always durable. */
    private static void createSchema(Connection c) throws SQLException {
        c.setAutoCommit(true);
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS account ("
                    + "id INTEGER PRIMARY KEY, "
                    + "owner VARCHAR(100) NOT NULL, "
                    + "balance NUMERIC(12,2) NOT NULL DEFAULT 0)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS ledger_entry ("
                    + "id INTEGER PRIMARY KEY, "
                    + "account_id INTEGER NOT NULL, "
                    + "amount NUMERIC(12,2) NOT NULL, "
                    + "memo VARCHAR(200))");
            // Deterministic starting point so repeated runs are comparable.
            st.executeUpdate("TRUNCATE TABLE ledger_entry");
            st.executeUpdate("TRUNCATE TABLE account");
        }
    }

    private static void runNormal(Connection c) throws SQLException {
        c.setAutoCommit(false);

        // --- Transaction #1: COMMITTED -------------------------------------------------
        // Insert accounts using a JDBC batch.
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO account(id, owner, balance) VALUES (?, ?, ?)")) {
            Object[][] accounts = {
                    {1, "alice", "100.00"},
                    {2, "bob", "250.50"},
                    {3, "carol", "0.00"},
            };
            for (Object[] a : accounts) {
                ps.setInt(1, (Integer) a[0]);
                ps.setString(2, (String) a[1]);
                ps.setBigDecimal(3, new java.math.BigDecimal((String) a[2]));
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            System.out.println("[legacy] tx1 account batch inserted=" + counts.length);
        }
        // A couple of committed ledger entries.
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ledger_entry(id, account_id, amount, memo) VALUES (?, ?, ?, ?)")) {
            insertLedger(ps, 10, 1, "50.00", "opening");
            insertLedger(ps, 11, 2, "-20.00", "coffee");
        }
        c.commit();
        System.out.println("[legacy] tx1 committed");

        // --- Transaction #2: ROLLED BACK ----------------------------------------------
        // These writes MUST appear in the trace but the transaction ends with rollback,
        // so a consumer that keeps only committed writes must discard them.
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ledger_entry(id, account_id, amount, memo) VALUES (?, ?, ?, ?)")) {
            insertLedger(ps, 90, 3, "999.99", "SHOULD_BE_ROLLED_BACK");
            insertLedger(ps, 91, 3, "888.88", "SHOULD_BE_ROLLED_BACK");
        }
        c.rollback();
        System.out.println("[legacy] tx2 rolled back (writes must be uncommitted)");

        // --- Read-back ----------------------------------------------------------------
        c.setAutoCommit(true);
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, account_id, amount, memo FROM ledger_entry ORDER BY id")) {
            int seen = 0;
            while (rs.next()) {
                seen++;
                System.out.println("[legacy] ledger row id=" + rs.getInt("id")
                        + " amount=" + rs.getBigDecimal("amount")
                        + " memo=" + rs.getString("memo"));
            }
            System.out.println("[legacy] committed ledger rows read=" + seen + " (expected 2)");
        }
    }

    /** "Nothing to process": a single SELECT that returns no rows, and no writes at all. */
    private static void runEmpty(Connection c) throws SQLException {
        c.setAutoCommit(true);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, owner, balance FROM account WHERE owner = ?")) {
            ps.setString(1, "__nobody__");
            try (ResultSet rs = ps.executeQuery()) {
                int seen = 0;
                while (rs.next()) seen++;
                System.out.println("[legacy] empty scenario rows read=" + seen + " (expected 0)");
            }
        }
    }

    private static void insertLedger(PreparedStatement ps, int id, int accountId, String amount, String memo)
            throws SQLException {
        ps.setInt(1, id);
        ps.setInt(2, accountId);
        ps.setBigDecimal(3, new java.math.BigDecimal(amount));
        ps.setString(4, memo);
        ps.executeUpdate();
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }
}
