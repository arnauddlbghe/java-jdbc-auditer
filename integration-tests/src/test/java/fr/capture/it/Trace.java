package fr.capture.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** In-memory view of a {@code trace.jsonl} file produced by the agent. */
public final class Trace {

    private static final ObjectMapper M = new ObjectMapper();

    private final List<JsonNode> events;

    private Trace(List<JsonNode> events) { this.events = events; }

    public static Trace load(Path traceFile) throws IOException {
        List<JsonNode> evs = new ArrayList<>();
        for (String line : Files.readAllLines(traceFile, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isEmpty()) evs.add(M.readTree(t));
        }
        return new Trace(evs);
    }

    public List<JsonNode> all() { return events; }

    public List<JsonNode> ofType(String type) {
        return events.stream()
                .filter(e -> type.equals(text(e, "event")))
                .collect(Collectors.toList());
    }

    public List<JsonNode> queries() { return ofType("query"); }

    public List<JsonNode> writeQueries() {
        return queries().stream()
                .filter(q -> isWriteKind(text(q, "kind")))
                .collect(Collectors.toList());
    }

    public JsonNode runEnd() {
        List<JsonNode> e = ofType("run_end");
        return e.isEmpty() ? null : e.get(e.size() - 1);
    }

    public JsonNode runStart() {
        List<JsonNode> e = ofType("run_start");
        return e.isEmpty() ? null : e.get(0);
    }

    public static boolean isWriteKind(String kind) {
        return "insert".equals(kind) || "update".equals(kind)
                || "delete".equals(kind) || "ddl".equals(kind) || "call".equals(kind);
    }

    public static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    public static long asLong(JsonNode n, String field, long dflt) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? dflt : v.asLong();
    }
}
