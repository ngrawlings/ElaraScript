package com.elara.script;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.elara.script.parser.Value;

/**
 * Example app that persists ElaraScript environment between processes using ElaraStateStore.
 *
 * Usage:
 *   java com.elara.script.ElaraPersistentRunner <script-file> <state-json-file>
 *
 * Example:
 *   java com.elara.script.ElaraPersistentRunner scripts/counter.es state.json
 */
public final class ElaraPersistentRunner {

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: ElaraPersistentRunner <script-file> <state-json-file>");
            System.exit(2);
        }

        Path scriptPath = Path.of(args[0]);
        Path statePath = Path.of(args[1]);

        String script;
        try {
            script = Files.readString(scriptPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Failed to read script: " + scriptPath);
            e.printStackTrace(System.err);
            System.exit(3);
            return;
        }

        ElaraScript engine = new ElaraScript();

        // 1) Load persisted state (JSON -> Map<String,Object> JSON-safe) using ORIGINAL store
        ElaraStateStore store = new ElaraStateStore();
        if (Files.exists(statePath)) {
            try {
                String json = Files.readString(statePath, StandardCharsets.UTF_8);
                store = ElaraStateStore.fromJson(json);
            } catch (Exception e) {
                System.err.println("Warning: failed to load state file. Starting fresh: " + statePath);
                e.printStackTrace(System.err);
                store = new ElaraStateStore();
            }
        }

        // 2) Convert JSON-safe raw state -> Map<String, ElaraScript.Value> initialEnv
        Map<String, Object> rawInputs = store.toRawInputs();
        Map<String, Value> initialEnv = toInitialEnv(rawInputs);

        // 3) Run with initial env restored
        Map<String, Value> envAfter;
        try {
            envAfter = engine.run(script, initialEnv);
        } catch (Exception e) {
            System.err.println("Script error:");
            e.printStackTrace(System.err);
            System.exit(1);
            return;
        }

        // 4) Snapshot envAfter into store (ORIGINAL store API)
        store.captureEnv(envAfter);

        // 5) Persist to disk
        String outJson = store.toJson();
        try {
            Files.writeString(statePath, outJson, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Failed to write state file: " + statePath);
            e.printStackTrace(System.err);
            System.exit(4);
            return;
        }

        // 6) Print a couple useful lines
        System.out.println("State saved to: " + statePath.toAbsolutePath());
        // Optional: print a stable “summary” value if present
        if (envAfter.containsKey("out")) {
            System.out.println("out = " + envAfter.get("out"));
        } else {
            System.out.println("(tip) Define `out` in your script to get a nice single-line summary.");
        }
    }

    /**
     * Converts JSON-safe values from ElaraStateStore into ElaraScript.Value for engine.run(..., initialEnv).
     *
     * JSON-safe allowed:
     *   null, boolean, number, string, List (arrays), List<List> (matrices)
     *
     * NOTE: Your language itself doesn't parse matrix literals, but the engine *does* support MATRIX as a Value type,
     * so we preserve it here (list-of-lists => MATRIX).
     */
    private static Map<String, Value> toInitialEnv(Map<String, Object> raw) {
        LinkedHashMap<String, Value> out = new LinkedHashMap<>();
        if (raw == null) return out;

        for (Map.Entry<String, Object> e : raw.entrySet()) {
            out.put(e.getKey(), fromJsonSafe(e.getValue()));
        }
        return out;
    }

    private static Value fromJsonSafe(Object v) {
        if (v == null) return Value.nil();

        if (v instanceof Boolean) return Value.bool((Boolean) v);
        if (v instanceof Number)  return Value.number(((Number) v).doubleValue());
        if (v instanceof String)  return Value.string((String) v);

        if (v instanceof List) {
            List<?> list = (List<?>) v;

            // If it's a list-of-lists, treat as MATRIX
            if (!list.isEmpty() && list.get(0) instanceof List) {
                List<List<Value>> rows = new ArrayList<>();
                for (Object rowObj : list) {
                    if (!(rowObj instanceof List)) {
                        throw new IllegalArgumentException("Matrix rows must be lists");
                    }
                    List<?> row = (List<?>) rowObj;
                    List<Value> r = new ArrayList<>(row.size());
                    for (Object item : row) r.add(fromJsonSafe(item));
                    rows.add(r);
                }
                return Value.matrix(rows);
            }

            // Otherwise treat as ARRAY
            List<Value> arr = new ArrayList<>(list.size());
            for (Object item : list) arr.add(fromJsonSafe(item));
            return Value.array(arr);
        }

        // (If you ever allow objects/maps in your language, you'd extend here)
        throw new IllegalArgumentException("Unsupported persisted value type: " + v.getClass().getName());
    }

    private ElaraPersistentRunner() {}
}
