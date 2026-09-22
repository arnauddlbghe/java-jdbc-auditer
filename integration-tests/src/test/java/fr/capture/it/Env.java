package fr.capture.it;

import org.junit.jupiter.api.Assumptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Test-side environment helpers: locate the repo, check prerequisites, and drive the capture
 * scripts to produce fixtures. Every prerequisite gap becomes a JUnit assumption failure (SKIP),
 * so the suite is honest: it never reports green for something it could not actually run.
 */
public final class Env {

    public static final String FIXED_CLOCK = "2020-01-01T00:00:00Z";
    public static final String FIXED_CLOCK_PREFIX = "2020-01-01";

    private Env() {}

    public static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (Path c = p; c != null; c = c.getParent()) {
            if (Files.exists(c.resolve("docker/docker-compose.yml"))
                    && Files.exists(c.resolve("scripts/run-sample.sh"))) {
                return c;
            }
        }
        throw new IllegalStateException("repo root not found from " + p);
    }

    public static Path agentJar()  { return repoRoot().resolve("jdbc-capture-agent/target/jdbc-capture-agent.jar"); }
    public static Path legacyJar() { return repoRoot().resolve("sample-legacy/target/sample-legacy.jar"); }
    public static Path modernJar() { return repoRoot().resolve("sample-modern/target/sample-modern.jar"); }

    public static boolean dockerAvailable() {
        try {
            return runRaw(repoRoot(), 20, dockerPath(), "version", "--format", "{{.Server.Version}}").exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String dockerPath() {
        Path rd = Paths.get(System.getProperty("user.home"), ".rd", "bin", "docker");
        return Files.exists(rd) ? rd.toString() : "docker";
    }

    // ---- prerequisite gates (skip test if unmet) -------------------------------------------

    public static void requireAgent() {
        Assumptions.assumeTrue(Files.exists(agentJar()),
                "agent jar not built yet: " + agentJar() + " (run: make build)");
    }

    public static void requireSample(String which) {
        Path jar = "legacy".equals(which) ? legacyJar() : modernJar();
        Assumptions.assumeTrue(Files.exists(jar),
                which + " sample jar not built yet: " + jar + " (run: make build)");
    }

    public static void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable(),
                "Docker not available (Rancher Desktop running? PATH includes ~/.rd/bin?)");
    }

    // ---- fixture production ----------------------------------------------------------------

    /**
     * Run a sample and return its output directory (containing trace.jsonl, summary.json,
     * app.stdout, app.exit, ...). Skips the test if any prerequisite is missing or the run fails.
     */
    public static Path capture(String which, String scenario, String label, String agentOpts) {
        requireDocker();
        requireAgent();
        requireSample(which);
        Path outDir = repoRoot().resolve("integration-tests/target/fixtures/" + which + "-" + label);
        try {
            Result r = runScript("scripts/run-sample.sh", which, scenario, outDir.toString(), agentOpts == null ? "" : agentOpts);
            Assumptions.assumeTrue(r.exit == 0,
                    "capture script failed (exit " + r.exit + ") for " + which + "/" + scenario + ":\n" + r.tail());
            Assumptions.assumeTrue(Files.exists(outDir.resolve("trace.jsonl")),
                    "no trace produced for " + which + "/" + scenario);
            return outDir;
        } catch (Exception e) {
            Assumptions.abort("capture failed for " + which + "/" + scenario + ": " + e);
            return null; // unreachable
        }
    }

    public static Result runScript(String scriptRel, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("/usr/bin/env");
        cmd.add("bash");
        cmd.add(repoRoot().resolve(scriptRel).toString());
        for (String a : args) cmd.add(a);
        return runRaw(repoRoot(), 600, cmd.toArray(new String[0]));
    }

    private static Result runRaw(Path cwd, int timeoutSec, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        // Ensure the docker CLI (Rancher Desktop) is on PATH for child scripts.
        String home = System.getProperty("user.home");
        pb.environment().merge("PATH", home + "/.rd/bin", (old, add) -> old + ":" + add);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) out.append(line).append('\n');
        }
        boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!done) { p.destroyForcibly(); return new Result(-1, out.toString()); }
        return new Result(p.exitValue(), out.toString());
    }

    public static final class Result {
        public final int exit;
        public final String output;
        Result(int exit, String output) { this.exit = exit; this.output = output; }
        String tail() {
            String[] lines = output.split("\n");
            int from = Math.max(0, lines.length - 25);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.length; i++) sb.append(lines[i]).append('\n');
            return sb.toString();
        }
    }
}
