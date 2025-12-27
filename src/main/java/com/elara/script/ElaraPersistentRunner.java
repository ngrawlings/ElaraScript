package com.elara.script;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        // 3) Run using the restored ExecutionState (keeps globals + env + instances in one object)
        Map<String, Value> snapshot;
        try {
            snapshot = engine.runWithState(script, exec_state);
        } catch (Exception e) {
            System.err.println("Script error:");
            e.printStackTrace(System.err);
            System.exit(1);
            return;
        }

        // 4) Export unified state -> JSON-safe map -> JSON string using ElaraStateStore writer
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

        // 5) Print a couple useful lines
        System.out.println("State saved to: " + statePath.toAbsolutePath());

        // Snapshot is structured; merge vars for a friendly "out" display
        Map<String, Value> merged = mergeSnapshotVars(snapshot);
        if (merged.containsKey("out")) {
            System.out.println("out = " + merged.get("out"));
        } else {
            System.out.println("(tip) Define `out` in your script to get a nice single-line summary.");
        }
    }

    /**
     * Merge vars from snapshot["environments"] frames (outer -> inner, inner overrides).
     * This is only for CLI display convenience.
     */
    private static Map<String, Value> mergeSnapshotVars(Map<String, Value> snapshot) {
        LinkedHashMap<String, Value> merged = new LinkedHashMap<>();
        if (snapshot == null) return merged;

        Value envsV = snapshot.get("environments");
        if (envsV == null || envsV.getType() != Value.Type.ARRAY || envsV.asArray() == null) return merged;

        List<Value> frames = envsV.asArray();
        for (Value frameV : frames) {
            if (frameV == null || frameV.getType() != Value.Type.MAP || frameV.asMap() == null) continue;

            Map<String, Value> frame = frameV.asMap();
            Value varsV = frame.get("vars");
            if (varsV == null || varsV.getType() != Value.Type.MAP || varsV.asMap() == null) continue;

            merged.putAll(varsV.asMap());
        }
        return merged;
    }

    private ElaraPersistentRunner() {}
}
