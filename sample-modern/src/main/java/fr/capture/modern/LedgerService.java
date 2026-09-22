package fr.capture.modern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * Same functional workload as {@code sample-legacy.LegacyApp} but with deliberately different SQL
 * (column order, {@code ON CONFLICT} upserts, a filtered read) so the two traces differ while the
 * committed end-state is equivalent. Transactions are declarative ({@code @Transactional}); the pooled
 * HikariCP connection delegates to the driver, which is where the capture agent observes commit/rollback.
 */
@Service
public class LedgerService {

    // Number of JDBC statement executions each step issues (a batchUpdate counts as ONE execution,
    // which is exactly how the agent records it). Used to publish EXPECTED_QUERIES so the
    // integration test can assert the agent captured each statement exactly once (no Hikari
    // double-counting). Keep in sync with the methods below.
    public static final int SCHEMA_STATEMENTS = 4;      // 2 CREATE + 2 TRUNCATE
    public static final int COMMITTED_STATEMENTS = 3;   // 1 batchUpdate + 2 inserts
    public static final int ROLLEDBACK_STATEMENTS = 2;  // 2 inserts (both run before the rollback)
    public static final int READ_STATEMENTS = 1;        // 1 count(*) select
    public static final int NORMAL_STATEMENTS =
            SCHEMA_STATEMENTS + COMMITTED_STATEMENTS + ROLLEDBACK_STATEMENTS + READ_STATEMENTS;

    private final JdbcTemplate jdbc;

    public LedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void createSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS account ("
                + "id INTEGER PRIMARY KEY, "
                + "owner VARCHAR(100) NOT NULL, "
                + "balance NUMERIC(12,2) NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS ledger_entry ("
                + "id INTEGER PRIMARY KEY, "
                + "account_id INTEGER NOT NULL, "
                + "amount NUMERIC(12,2) NOT NULL, "
                + "memo VARCHAR(200))");
        // Deterministic starting point.
        jdbc.execute("TRUNCATE TABLE ledger_entry");
        jdbc.execute("TRUNCATE TABLE account");
    }

    /** Committed transaction: upsert accounts via a batch, then insert two ledger entries. */
    @Transactional
    public void runCommittedTransaction() {
        List<Object[]> accounts = Arrays.asList(
                new Object[]{1, "alice", new BigDecimal("100.00")},
                new Object[]{2, "bob", new BigDecimal("250.50")},
                new Object[]{3, "carol", new BigDecimal("0.00")});
        // Different SQL from legacy: explicit upsert.
        jdbc.batchUpdate(
                "INSERT INTO account(id, owner, balance) VALUES (?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET owner = EXCLUDED.owner, balance = EXCLUDED.balance",
                accounts);
        // Different column order in the INSERT than legacy.
        jdbc.update("INSERT INTO ledger_entry(id, amount, memo, account_id) VALUES (?, ?, ?, ?)",
                10, new BigDecimal("50.00"), "opening", 1);
        jdbc.update("INSERT INTO ledger_entry(id, amount, memo, account_id) VALUES (?, ?, ?, ?)",
                11, new BigDecimal("-20.00"), "coffee", 2);
    }

    /**
     * Deliberately rolled-back transaction: writes happen, then a RuntimeException triggers a
     * Spring-managed rollback. The writes must be visible in the trace under a transaction that
     * ends with a rollback (i.e. not committed).
     */
    @Transactional
    public void runRolledBackTransaction() {
        jdbc.update("INSERT INTO ledger_entry(id, amount, memo, account_id) VALUES (?, ?, ?, ?)",
                90, new BigDecimal("999.99"), "SHOULD_BE_ROLLED_BACK", 3);
        jdbc.update("INSERT INTO ledger_entry(id, amount, memo, account_id) VALUES (?, ?, ?, ?)",
                91, new BigDecimal("888.88"), "SHOULD_BE_ROLLED_BACK", 3);
        throw new IllegalStateException("intentional rollback");
    }

    /** Read back only what a filter matches — different SQL shape than legacy's plain ordered read. */
    public long readCommittedLedger() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entry WHERE memo <> ?", Integer.class, "SHOULD_BE_ROLLED_BACK");
        return n == null ? 0 : n;
    }

    /** "Nothing to process": a SELECT that returns nothing, no writes. */
    public long countMissing() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM account WHERE owner = ?", Integer.class, "__nobody__");
        return n == null ? 0 : n;
    }
}
