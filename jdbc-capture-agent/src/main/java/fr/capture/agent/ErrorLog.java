package fr.capture.agent;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * The agent's own error sink. Nothing here may ever throw back into the application:
 * every internal failure is swallowed and, best-effort, appended to {@code agent-errors.log}.
 */
public final class ErrorLog {

    private static volatile Path file;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private ErrorLog() {}

    static void init(Path outDir) {
        try {
            Files.createDirectories(outDir);
            file = outDir.resolve("agent-errors.log");
        } catch (Throwable ignored) {
            file = null;
        }
    }

    public static void error(String context, Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.print(Instant.now());
            pw.print(" [");
            pw.print(Thread.currentThread().getName());
            pw.print("] ");
            pw.println(context);
            if (t != null) t.printStackTrace(pw);
            pw.flush();
            append(sw.toString());
        } catch (Throwable ignored) {
            // last resort: give up silently, never disturb the application
        }
    }

    public static void error(String context) {
        error(context, null);
    }

    private static synchronized void append(String s) {
        Path f = file;
        if (f == null) {
            // no output dir yet: fall back to stderr so the message is not lost entirely
            System.err.print("[jdbc-capture] " + s);
            return;
        }
        try {
            Files.write(f, s.getBytes(UTF8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (Throwable t) {
            System.err.print("[jdbc-capture] " + s);
        }
    }
}
