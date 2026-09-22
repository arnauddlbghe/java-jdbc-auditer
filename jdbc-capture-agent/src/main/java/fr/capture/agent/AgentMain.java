package fr.capture.agent;

import fr.capture.agent.advice.ConnectAdvice;
import fr.capture.agent.runtime.Capture;
import fr.capture.agent.trace.Json;
import fr.capture.agent.trace.TraceWriter;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;

/**
 * Entry point of the JDBC capture agent (both {@code premain} and {@code agentmain}).
 * <p>
 * The whole method is wrapped so that <b>any</b> failure leaves the application running without
 * instrumentation, per the fail-safe requirement.
 */
public final class AgentMain {

    private AgentMain() {}

    public static void premain(String args, Instrumentation inst) {
        install(args, inst, "premain");
    }

    public static void agentmain(String args, Instrumentation inst) {
        install(args, inst, "agentmain");
    }

    private static void install(String args, Instrumentation inst, String phase) {
        Options opts;
        try {
            opts = Options.parse(args);
        } catch (Throwable t) {
            opts = new Options();
        }
        try {
            ErrorLog.init(opts.outDir);
        } catch (Throwable ignored) { /* ErrorLog handles its own failure */ }

        // Flush option-parse warnings now that the error log has an output directory.
        for (String w : opts.warnings) ErrorLog.error(w);

        try {
            Path trace = opts.outDir.resolve("trace.jsonl");
            TraceWriter writer = new TraceWriter(trace, opts.queueCapacity);
            writer.start();
            Capture.install(opts, writer);

            emitRunStart(opts, phase);
            installShutdownHook();
            installByteBuddy(inst);

            System.err.println("[jdbc-capture] agent installed (" + phase + "), out=" + opts.outDir
                    + ", byte-buddy advice on Driver#connect / DataSource#getConnection");
        } catch (Throwable t) {
            // Absolute fail-safe: never prevent the application from starting.
            ErrorLog.error("Agent failed to install; application continues without capture", t);
            System.err.println("[jdbc-capture] agent DISABLED (install failed): " + t);
        }
    }

    private static void installByteBuddy(Instrumentation inst) {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Reiterating.INSTANCE)
                // Never touch the agent, Byte Buddy itself, or the JDK: "do not instrument JDK classes".
                .ignore(nameStartsWith("fr.capture.")
                        .or(nameStartsWith("net.bytebuddy."))
                        .or(nameStartsWith("java."))
                        .or(nameStartsWith("javax."))
                        .or(nameStartsWith("jdk."))
                        .or(nameStartsWith("sun."))
                        .or(nameStartsWith("com.sun.")))
                .type(not(isInterface())
                        .and(hasSuperType(named("java.sql.Driver").or(named("javax.sql.DataSource")))))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            JavaModule module,
                                                            ProtectionDomain pd) {
                        return builder.visit(Advice.to(ConnectAdvice.class)
                                .on(namedOneOf("connect", "getConnection").and(returns(named("java.sql.Connection")))));
                    }
                })
                .installOn(inst);
    }

    private static void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                Capture c = Capture.get();
                if (c != null) c.shutdown(null);
            }
        }, "jdbc-capture-shutdown"));
    }

    private static void emitRunStart(Options opts, String phase) {
        Capture c = Capture.get();
        if (c == null) return;
        try {
            Json e = c.event("run_start", 0, 0)
                    .add("phase", phase)
                    .add("started", c.startIso())
                    .add("java_version", System.getProperty("java.version"))
                    .add("java_vendor", System.getProperty("java.vendor"))
                    // Best effort: the real main() argv is not available in premain; sun.java.command
                    // exposes "<main-class> <args...>" (or the -jar path), which is the closest we get.
                    .add("command", System.getProperty("sun.java.command"))
                    .add("options", opts.toString())
                    .addRaw("jvm_args", jvmArgsJson())
                    .addRaw("sys_props", sysPropsJson());
            c.emit(e);
        } catch (Throwable t) {
            ErrorLog.error("emitRunStart failed", t);
        }
    }

    private static final String[] USEFUL_PROPS = {
            "os.name", "os.arch", "os.version", "java.vm.name", "java.vm.version",
            "user.timezone", "file.encoding", "user.name", "java.runtime.version"
    };

    private static String sysPropsJson() {
        Json o = Json.obj();
        for (String k : USEFUL_PROPS) {
            String v = System.getProperty(k);
            if (v != null) o.add(k, v);
        }
        return o.end();
    }

    private static String jvmArgsJson() {
        StringBuilder sb = new StringBuilder("[");
        try {
            List<String> in = ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (int i = 0; i < in.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(Json.quote(in.get(i)));
            }
        } catch (Throwable ignored) {}
        return sb.append(']').toString();
    }
}
