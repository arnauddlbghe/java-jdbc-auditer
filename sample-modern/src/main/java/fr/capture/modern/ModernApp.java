package fr.capture.modern;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Spring Boot 3 / Java 25 / HikariCP counterpart of {@code sample-legacy}. It performs the same
 * functional workload (accounts + ledger entries, one committed transaction and one deliberately
 * rolled-back transaction, plus a "nothing to process" scenario) but through Spring's
 * {@code JdbcTemplate} and with intentionally different SQL, so the two capture traces can be
 * compared.
 *
 * <p>The application is non-web: it runs a {@link CommandLineRunner} then closes the context and
 * exits, which is what the capture scripts expect.</p>
 */
@SpringBootApplication
public class ModernApp {

    public static void main(String[] args) {
        // web-application-type=none is set in application.properties; the context closes after runners.
        ConfigurableApplicationContext ctx = SpringApplication.run(ModernApp.class, args);
        int code = SpringApplication.exit(ctx);
        System.exit(code);
    }

    /** Drives the workload according to the first CLI argument: {@code normal} (default) or {@code empty}. */
    @Component
    @Order(1)
    static class WorkloadRunner implements CommandLineRunner {

        private final LedgerService ledger;

        WorkloadRunner(LedgerService ledger) {
            this.ledger = ledger;
        }

        @Override
        public void run(String... args) {
            String scenario = (args.length > 0) ? args[0] : "normal";
            System.out.println("[modern] scenario=" + scenario);
            ledger.createSchema();

            if ("empty".equals(scenario)) {
                long n = ledger.countMissing();
                System.out.println("[modern] empty scenario rows read=" + n + " (expected 0)");
            } else {
                ledger.runCommittedTransaction();
                System.out.println("[modern] committed transaction done");
                try {
                    ledger.runRolledBackTransaction();
                } catch (RuntimeException expected) {
                    System.out.println("[modern] rolled-back transaction: " + expected.getMessage());
                }
                long committed = ledger.readCommittedLedger();
                System.out.println("[modern] committed ledger rows read=" + committed + " (expected 2)");
                // Announce how many JDBC statements this run issued so the integration test can
                // assert the agent captured exactly that many query events (no Hikari double count).
                System.out.println("EXPECTED_QUERIES=" + LedgerService.NORMAL_STATEMENTS);
            }
            System.out.println("[modern] done");
        }
    }
}
