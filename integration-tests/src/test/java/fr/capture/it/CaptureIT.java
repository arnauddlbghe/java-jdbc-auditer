package fr.capture.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end assertions over traces produced by running the two samples in Docker (JVM 8 and 25)
 * with the SAME agent jar. Prerequisite gaps (missing jars, no Docker, sample not printing an
 * expected marker) are turned into SKIPs, never fake passes.
 */
@DisplayName("JDBC capture agent — integration")
class CaptureIT {

    // Fixtures are produced once and reused across tests within a JVM.
    private static final java.util.Map<String, Path> FX = new java.util.concurrent.ConcurrentHashMap<>();

    private static Path fixture(String which, String scenario, String label, String opts) {
        String key = which + "/" + label;
        Path cached = FX.get(key);
        if (cached != null) return cached;
        Path dir = Env.capture(which, scenario, label, opts); // may skip (TestAbortedException)
        FX.put(key, dir);
        return dir;
    }

    private static Trace trace(Path dir) throws IOException {
        return Trace.load(dir.resolve("trace.jsonl"));
    }

    private static String stdout(Path dir) throws IOException {
        Path f = dir.resolve("app.stdout");
        return Files.exists(f) ? new String(Files.readAllBytes(f), StandardCharsets.UTF_8) : "";
    }

    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("legacy: rollback writes are attributable to their tx and not committed")
    void rollbackWritesAreUnvalidated() throws IOException {
        Path dir = fixture("legacy", "normal", "normal", null);
        Trace t = trace(dir);

        // committed logical transactions, keyed by conn:tx
        Set<String> committed = new HashSet<>();
        Set<String> rolledBack = new HashSet<>();
        for (JsonNode e : t.ofType("tx")) {
            String kind = Trace.text(e, "kind");
            String k = Trace.text(e, "conn") + ":" + Trace.text(e, "tx");
            if ("commit".equals(kind)) committed.add(k);
            else if (kind != null && kind.startsWith("rollback")) rolledBack.add(k);
        }
        assertFalse(rolledBack.isEmpty(),
                "expected the legacy sample to perform at least one rollback (voluntary rollback scenario)");

        // at least one data write belongs to a tx that was never committed → a consumer can drop it
        boolean uncommittedWriteExists = false;
        for (JsonNode q : t.writeQueries()) {
            if ("ddl".equals(Trace.text(q, "kind"))) continue;
            String k = Trace.text(q, "conn") + ":" + Trace.text(q, "tx");
            if (!committed.contains(k)) { uncommittedWriteExists = true; break; }
        }
        assertTrue(uncommittedWriteExists,
                "expected at least one data write attributable to a rolled-back / uncommitted tx");
    }

    @Test
    @DisplayName("modern/Hikari: no query is double-counted")
    void noDoubleCountOnHikari() throws IOException {
        Path dir = fixture("modern", "normal", "normal", null);
        Trace t = trace(dir);

        // Every query references a conn that was actually opened (no phantom proxy layer).
        Set<String> openedConns = new HashSet<>();
        for (JsonNode e : t.ofType("conn_open")) openedConns.add(Trace.text(e, "conn"));
        for (JsonNode q : t.queries()) {
            assertTrue(openedConns.contains(Trace.text(q, "conn")),
                    "query on conn " + Trace.text(q, "conn") + " has no matching conn_open (double-wrap?)");
        }

        // Authoritative check: the modern sample announces how many statements it runs.
        Integer expected = parseMarker(stdout(dir), "EXPECTED_QUERIES");
        Assumptions.assumeTrue(expected != null,
                "modern sample must print 'EXPECTED_QUERIES=<n>' on stdout for the exact double-count check");
        long actual = t.queries().stream()
                .filter(q -> !q.path("batch").asBoolean(false))
                .count()
              + t.queries().stream().filter(q -> q.path("batch").asBoolean(false)).count();
        assertEquals(expected.intValue(), actual,
                "captured query count must equal what the app issued (no Hikari double counting)");
    }

    @Test
    @DisplayName("frozen clock: agent event timestamps start at the given date on BOTH JVMs")
    void frozenClockIdenticalPrefix() throws IOException {
        Path legacy = fixture("legacy", "normal", "clock", "clock=" + Env.FIXED_CLOCK);
        Path modern = fixture("modern", "normal", "clock", "clock=" + Env.FIXED_CLOCK);

        for (Path dir : new Path[]{legacy, modern}) {
            Trace t = trace(dir);
            JsonNode start = t.runStart();
            assertNotNull(start, "run_start missing in " + dir);
            String ts = Trace.text(start, "ts");
            assertNotNull(ts);
            assertTrue(ts.startsWith(Env.FIXED_CLOCK_PREFIX),
                    "agent event ts should start at the frozen date " + Env.FIXED_CLOCK_PREFIX + " but was " + ts
                            + " (" + dir.getFileName() + ")");
        }
        // Same anchor date on both sides.
        assertEquals(Env.FIXED_CLOCK_PREFIX,
                Trace.text(trace(legacy).runStart(), "ts").substring(0, Env.FIXED_CLOCK_PREFIX.length()));
        assertEquals(Env.FIXED_CLOCK_PREFIX,
                Trace.text(trace(modern).runStart(), "ts").substring(0, Env.FIXED_CLOCK_PREFIX.length()));
    }

    @Test
    @DisplayName("empty scenario: trace exists with no data writes")
    void emptyScenarioNoWrites() throws IOException {
        Path dir = fixture("legacy", "empty", "empty", null);
        Trace t = trace(dir);

        assertNotNull(t.runStart(), "run_start expected even for an empty run");
        assertNotNull(t.runEnd(), "run_end expected even for an empty run");
        long dataWrites = t.queries().stream()
                .map(q -> Trace.text(q, "kind"))
                .filter(k -> "insert".equals(k) || "update".equals(k) || "delete".equals(k))
                .count();
        assertEquals(0, dataWrites, "empty scenario must not produce data writes");
    }

    @Test
    @DisplayName("fail-safe: app boots with an invalid agent option, error is logged")
    void appBootsWithInvalidOption() throws IOException {
        Path dir = fixture("legacy", "normal", "badopt", "rows=bogus");

        String exit = readTrim(dir.resolve("app.exit"));
        assertEquals("0", exit, "application must start and finish normally despite a bad agent option");

        Path errLog = dir.resolve("agent-errors.log");
        assertTrue(Files.exists(errLog), "agent-errors.log should record the invalid option");
        String err = new String(Files.readAllBytes(errLog), StandardCharsets.UTF_8).toLowerCase();
        assertTrue(err.contains("rows") || err.contains("invalid") || err.contains("bogus"),
                "agent-errors.log should mention the invalid option; was:\n" + err);

        assertNotNull(trace(dir).runStart(), "a trace should still be produced");
    }

    @Test
    @DisplayName("overhead: agent runtime cost is measured and reported")
    void overheadIsMeasured() throws Exception {
        Env.requireDocker();
        Env.requireAgent();
        Env.requireSample("modern");
        // Fast pass: 2 runs each side. Enable a longer run with -Dit.overhead.runs=N.
        String runs = System.getProperty("it.overhead.runs", "2");
        Env.Result r = Env.runScript("scripts/measure-overhead.sh", "25", "sample-modern/target/sample-modern.jar", "normal", runs);
        Assumptions.assumeTrue(r.exit == 0, "measure-overhead.sh could not run:\n" + r.tail());

        Matcher m = Pattern.compile("overhead:\\s*(-?\\d+)\\s*ms\\s*\\(([^)]*)\\)").matcher(r.output);
        assertTrue(m.find(), "overhead line not found in measure-overhead output:\n" + r.tail());
        System.out.println("[overhead] " + m.group(1) + " ms (" + m.group(2) + ")  [reported by integration test]");
    }

    // ---------------------------------------------------------------------------------------

    private static Integer parseMarker(String text, String key) {
        Matcher m = Pattern.compile(Pattern.quote(key) + "=(\\d+)").matcher(text);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static String readTrim(Path p) throws IOException {
        return Files.exists(p) ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim() : "";
    }
}
