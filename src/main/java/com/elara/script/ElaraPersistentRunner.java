package com.elara.script;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.elara.script.parser.Environment;
import com.elara.script.parser.ExecutionState;
import com.elara.script.parser.Value;

/**
 * Example app that persists ElaraScript ExecutionState between processes.
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

        // 1) Load persisted JSON (JSON-safe map) using ElaraStateStore's tiny JSON parser.
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

        // 2) Restore unified ExecutionState from JSON-safe
        ExecutionState exec_state;
        try {
            Map<String, Object> jsonSafe = store.toRawInputs();
            exec_state = ExecutionState.importJsonSafe(jsonSafe);
        } catch (Exception e) {
            System.err.println("Warning: state file was malformed. Starting fresh: " + statePath);
            e.printStackTrace(System.err);
            exec_state = new ExecutionState();
        }

        // 3) Build initialEnv from exec_state (root env vars). We persist only root env + globals.
        Map<String, Value> initialEnv = extractRootEnvVars(exec_state);

        // 4) Run with restored instances + env
        Map<String, Value> envAfter;
        try {
            envAfter = engine.run(script, exec_state.liveInstances, initialEnv);
        } catch (Exception e) {
            System.err.println("Script error:");
            e.printStackTrace(System.err);
            System.exit(1);
            return;
        }

        // 5) Update exec_state env to reflect the post-run root environment
        exec_state.env = new Environment(envAfter);

        // 6) Export unified state -> JSON-safe map -> JSON string using ElaraStateStore writer
        Map<String, Object> outJsonSafe = exec_state.exportJsonSafe();
        String outJson = new ElaraStateStore(outJsonSafe).toJson();

        try {
            Files.writeString(statePath, outJson, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Failed to write state file: " + statePath);
            e.printStackTrace(System.err);
            System.exit(4);
            return;
        }

        // 7) Print a couple useful lines
        System.out.println("State saved to: " + statePath.toAbsolutePath());
        if (envAfter.containsKey("out")) {
            System.out.println("out = " + envAfter.get("out"));
        } else {
            System.out.println("(tip) Define `out` in your script to get a nice single-line summary.");
        }
    }

    /**
     * Extract the root env vars from exec_state.env.
     * If env has frames, use inner-most frame vars.
     * If anything is missing, return empty.
     */
    private static Map<String, Value> extractRootEnvVars(ExecutionState exec_state) {
        if (exec_state == null || exec_state.env == null) return new LinkedHashMap<>();

        List<Map<String, Value>> frames = exec_state.env.snapshotFrames();
        if (frames == null || frames.isEmpty()) return new LinkedHashMap<>();

        Map<String, Value> inner = frames.get(frames.size() - 1);
        if (inner == null) return new LinkedHashMap<>();

        Value varsV = inner.get("vars");
        if (varsV == null || varsV.getType() != Value.Type.MAP || varsV.asMap() == null) {
            return new LinkedHashMap<>();
        }

        return new LinkedHashMap<>(varsV.asMap());
    }

    private ElaraPersistentRunner() {}
}
