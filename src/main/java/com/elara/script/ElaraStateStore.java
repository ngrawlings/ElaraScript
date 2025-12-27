package com.elara.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.elara.script.parser.Environment;
import com.elara.script.parser.ExecutionState;
import com.elara.script.parser.Value;

/**
 * ElaraStateStore
 *
 * Independent persistence helper for ElaraScript.
 *
 * Purpose:
 * - Keep ElaraScript engine pure (inputs -> outputs)
 * - Provide a clean host-side way to persist and restore state
 * - Serialize state to/from a JSON string without external libraries
 *
 * What is "state"?
 * - A Map<String, Object> where values are JSON-safe:
 *     null, boolean, number, string, List (arrays), List<List> (matrices)
 *
 * Usage pattern:
 *   ElaraStateStore store = new ElaraStateStore();
 *
 *   // After run:
 *   store.captureOutputs(runResult.outputs());  // or captureEnv(runResult.debugEnv())
 *   String json = store.toJson();
 *
 *   // Later:
 *   ElaraStateStore restored = ElaraStateStore.fromJson(json);
 *   Map<String,Object> rawInputs = restored.toRawInputs();
 *   engine.run(script, shape, rawInputs);
 */
public final class ElaraStateStore {

    // Reserved keys used when storing full ExecutionState.
    // These are namespaced to avoid collisions with user variables.
    private static final String KEY_EXEC = "__elara_exec";
    private static final String KEY_ENV = "env";             // Map<String, Object>
    private static final String KEY_GLOBAL = "global";       // Map<String, Object>
    private static final String KEY_INSTANCES = "instances"; // List<Map<String,Object>>

    private final LinkedHashMap<String, Object> state;

    public ElaraStateStore() {
        this.state = new LinkedHashMap<>();
    }

    public ElaraStateStore(Map<String, Object> initial) {
        this.state = new LinkedHashMap<>();
        if (initial != null) {
            for (Map.Entry<String, Object> e : initial.entrySet()) {
                this.state.put(e.getKey(), deepCopyJsonSafe(e.getValue()));
            }
        }
    }

    /**
     * Capture only the validated outputs (recommended default).
     * Values are converted to JSON-safe types.
     */
    public ElaraStateStore captureOutputs(Map<String, Value> outputs) {
        if (outputs == null) return this;
        for (Map.Entry<String, Value> e : outputs.entrySet()) {
            state.put(e.getKey(), ValueCodec.toPlainJava(e.getValue()));
        }
        return this;
    }

    /**
     * Capture the full debug environment (includes intermediates). Requires includeDebugEnv=true.
     */
    public ElaraStateStore captureEnv(Map<String, Value> env) {
        if (env == null) return this;
        for (Map.Entry<String, Value> e : env.entrySet()) {
            state.put(e.getKey(), ValueCodec.toPlainJava(e.getValue()));
        }
        return this;
    }

    /**
     * Capture a full ExecutionState (environment + live instances).
     *
     * Storage format (all JSON-safe):
     * {
     *   "__elara_exec": {
     *     "env": { ... },
     *     "global": { ... },
     *     "instances": [ { key, className, uuid, state }, ... ]
     *   }
     * }
     */
    public ElaraStateStore captureExecutionState(ExecutionState execState) {
        if (execState == null) return this;

        LinkedHashMap<String, Object> exec = new LinkedHashMap<>();

        // --- Environment ---
        // Persist root env vars (inner-most frame) + global vars.
        // This intentionally does NOT persist transient call-stack frames.
        Map<String, Object> envOut = new LinkedHashMap<>();
        Map<String, Object> globalOut = new LinkedHashMap<>();

        if (execState.env != null) {
            List<Map<String, Value>> frames = execState.env.snapshotFrames();

            // frame[0] is synthetic global frame.
            if (frames != null && !frames.isEmpty()) {
                Map<String, Value> gFrame = frames.get(0);
                Value gVarsV = (gFrame == null) ? null : gFrame.get("vars");
                if (gVarsV != null && gVarsV.getType() == Value.Type.MAP) {
                    for (Map.Entry<String, Value> e : gVarsV.asMap().entrySet()) {
                        globalOut.put(e.getKey(), ValueCodec.toPlainJava(e.getValue()));
                    }
                }

                // inner-most frame is last (for normal runs, this is effectively your root env).
                Map<String, Value> inner = frames.get(frames.size() - 1);
                Value varsV = (inner == null) ? null : inner.get("vars");
                if (varsV != null && varsV.getType() == Value.Type.MAP) {
                    for (Map.Entry<String, Value> e : varsV.asMap().entrySet()) {
                        envOut.put(e.getKey(), ValueCodec.toPlainJava(e.getValue()));
                    }
                }
            }
        }

        exec.put(KEY_ENV, envOut);
        exec.put(KEY_GLOBAL, globalOut);

        // --- Live Instances ---
        List<Object> instancesOut = new ArrayList<>();
        if (execState.liveInstances != null) {
            for (Map.Entry<String, Value.ClassInstance> e : execState.liveInstances.entrySet()) {
                String key = e.getKey();
                Value.ClassInstance inst = e.getValue();
                if (inst == null) continue;

                LinkedHashMap<String, Object> rec = new LinkedHashMap<>();
                rec.put("key", key);
                rec.put("className", inst.className);
                rec.put("uuid", inst.uuid);
                rec.put("state", ValueCodec.toPlainJava(Value.map(inst._this)));
                instancesOut.add(rec);
            }
        }
        exec.put(KEY_INSTANCES, instancesOut);

        state.put(KEY_EXEC, exec);
        return this;
    }

    /**
     * Restore an ExecutionState from this store.
     * If no execution-state block exists, returns a blank state.
     */
    public ExecutionState toExecutionState() {
        Object execRaw = state.get(KEY_EXEC);
        if (!(execRaw instanceof Map)) {
            return new ExecutionState();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> exec = (Map<String, Object>) execRaw;

        // --- Restore globals ---
        Object gRaw = exec.get(KEY_GLOBAL);
        if (gRaw instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> gm = (Map<String, Object>) gRaw;
            Environment.global.clear();
            for (Map.Entry<String, Object> e : gm.entrySet()) {
                Environment.global.put(e.getKey(), ValueCodec.fromPlainJava(e.getValue()));
            }
        }

        // --- Restore env ---
        Map<String, Value> envState = new LinkedHashMap<>();
        Object envRaw = exec.get(KEY_ENV);
        if (envRaw instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> em = (Map<String, Object>) envRaw;
            for (Map.Entry<String, Object> e : em.entrySet()) {
                envState.put(e.getKey(), ValueCodec.fromPlainJava(e.getValue()));
            }
        }

        // --- Restore instances ---
        LinkedHashMap<String, Value.ClassInstance> instances = new LinkedHashMap<>();
        Object instRaw = exec.get(KEY_INSTANCES);
        if (instRaw instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> li = (List<Object>) instRaw;
            for (Object o : li) {
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> rec = (Map<String, Object>) o;

                String key = (rec.get("key") instanceof String) ? (String) rec.get("key") : null;
                String className = (rec.get("className") instanceof String) ? (String) rec.get("className") : null;
                String uuid = (rec.get("uuid") instanceof String) ? (String) rec.get("uuid") : null;
                if (key == null || className == null || uuid == null) continue;

                Value.ClassInstance inst = new Value.ClassInstance(className, uuid);

                Object st = rec.get("state");
                Value stV = ValueCodec.fromPlainJava(st);
                if (stV != null && stV.getType() == Value.Type.MAP && stV.asMap() != null) {
                    inst._this.putAll(stV.asMap());
                }
                instances.put(key, inst);
            }
        }

        return new ExecutionState(instances, envState);
    }

    /** Put a raw JSON-safe value into the store (used for patch/state sync). */
    public ElaraStateStore putRaw(String key, Object rawJsonSafeValue) {
        state.put(key, deepCopyJsonSafe(rawJsonSafeValue));
        return this;
    }

    /**
     * Capture a raw input map (already JSON-safe, or will be coerced to JSON-safe).
     */
    public ElaraStateStore captureRawInputs(Map<String, Object> rawInputs) {
        if (rawInputs == null) return this;
        for (Map.Entry<String, Object> e : rawInputs.entrySet()) {
            state.put(e.getKey(), deepCopyJsonSafe(e.getValue()));
        }
        return this;
    }

    /** Remove a key from state. */
    public ElaraStateStore remove(String key) {
        state.remove(key);
        return this;
    }

    /** Get a JSON-safe value. */
    public Object get(String key) {
        return state.get(key);
    }

    /** Set a JSON-safe value. */
    public ElaraStateStore put(String key, Object jsonSafeValue) {
        state.put(key, deepCopyJsonSafe(jsonSafeValue));
        return this;
    }

    /** Returns a copy of the current state as raw inputs. */
    public Map<String, Object> toRawInputs() {
        return new LinkedHashMap<>(state);
    }

    // ===================== JSON SERIALIZATION =====================

    /** Serialize this state map to a JSON string. */
    public String toJson() {
        StringBuilder sb = new StringBuilder(256);
        Json.writeValue(sb, state);
        return sb.toString();
    }

    /** Parse and restore an ElaraStateStore from JSON. */
    public static ElaraStateStore fromJson(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("State JSON must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;
        return new ElaraStateStore(m);
    }

    // ===================== JSON-SAFE NORMALIZATION =====================

    private static Object deepCopyJsonSafe(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean) return v;
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) return v;

        if (v instanceof Value) {
            return ValueCodec.toPlainJava((Value) v);
        }

        if (v instanceof List) {
            List<?> src = (List<?>) v;
            List<Object> out = new ArrayList<>(src.size());
            for (Object item : src) out.add(deepCopyJsonSafe(item));
            return out;
        }

        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> src = (Map<Object, Object>) v;
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> e : src.entrySet()) {
                if (!(e.getKey() instanceof String)) {
                    throw new IllegalArgumentException("Only string keys allowed in state");
                }
                out.put((String) e.getKey(), deepCopyJsonSafe(e.getValue()));
            }
            return out;
        }

        throw new IllegalArgumentException("Unsupported state type: " + v.getClass().getName());
    }

    // ===================== VALUE CODEC =====================

    /**
     * Converts Value into JSON-safe Java types.
     * This is host-side only and keeps the engine clean.
     */
    public static final class ValueCodec {
        private ValueCodec() {}

        public static Object toPlainJava(Value v) {
            if (v == null) return null;
            switch (v.getType()) {
                case NULL:
                    return null;
                case NUMBER:
                    return v.asNumber();
                case BOOL:
                    return v.asBool();
                case STRING:
                    return v.asString();
                case FUNC:
                    return v.asString();
                case ARRAY: {
                    List<Object> out = new ArrayList<>();
                    for (Value item : v.asArray()) out.add(toPlainJava(item));
                    return out;
                }
                case MATRIX: {
                    List<Object> rows = new ArrayList<>();
                    for (List<Value> row : v.asMatrix()) {
                        List<Object> r = new ArrayList<>();
                        for (Value item : row) r.add(toPlainJava(item));
                        rows.add(r);
                    }
                    return rows;
                }
                case MAP: {
                    Map<String, Object> out = new LinkedHashMap<>();
                    Map<String, Value> m = v.asMap();
                    if (m != null) {
                        for (Map.Entry<String, Value> e : m.entrySet()) {
                            out.put(e.getKey(), toPlainJava(e.getValue()));
                        }
                    }
                    return out;
                }
                default:
                    throw new IllegalStateException("Unsupported Value type: " + v.getType());
            }
        }

        /**
         * Converts JSON-safe Java types back into a Value.
         *
         * Notes:
         * - Numbers are treated as NUMBER (double)
         * - Lists are treated as ARRAY by default
         * - If a List is a "list of lists", it becomes MATRIX
         * - Maps become MAP (string keys only)
         */
        public static Value fromPlainJava(Object o) {
            if (o == null) return Value.nil();
            if (o instanceof Value) return (Value) o;
            if (o instanceof Boolean) return Value.bool((Boolean) o);
            if (o instanceof Number) return Value.number(((Number) o).doubleValue());
            if (o instanceof String) return Value.string((String) o);

            if (o instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> src = (List<Object>) o;

                boolean allLists = !src.isEmpty();
                for (Object it : src) {
                    if (!(it instanceof List)) {
                        allLists = false;
                        break;
                    }
                }

                if (allLists) {
                    List<List<Value>> rows = new ArrayList<>(src.size());
                    for (Object rowObj : src) {
                        @SuppressWarnings("unchecked")
                        List<Object> rowSrc = (List<Object>) rowObj;
                        List<Value> row = new ArrayList<>(rowSrc.size());
                        for (Object cell : rowSrc) row.add(fromPlainJava(cell));
                        rows.add(row);
                    }
                    return Value.matrix(rows);
                }

                List<Value> out = new ArrayList<>(src.size());
                for (Object it : src) out.add(fromPlainJava(it));
                return Value.array(out);
            }

            if (o instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> src = (Map<Object, Object>) o;
                LinkedHashMap<String, Value> out = new LinkedHashMap<>();
                for (Map.Entry<Object, Object> e : src.entrySet()) {
                    if (!(e.getKey() instanceof String)) {
                        throw new IllegalArgumentException("Only string keys allowed in state");
                    }
                    out.put((String) e.getKey(), fromPlainJava(e.getValue()));
                }
                return Value.map(out);
            }

            throw new IllegalArgumentException("Unsupported state type for Value decode: " + o.getClass().getName());
        }
    }

    // ===================== TINY JSON PARSER/WRITER =====================
    // ... keep the rest of your Json implementation unchanged ...
    // (I didn’t paste the whole thing again here to avoid noise.)
    private static final class Json {
        private Json() {}
        // your existing Json implementation...
        // (unchanged)
        // ...
        static Object parse(String s) { throw new UnsupportedOperationException("paste your existing Json class here"); }
        static void writeValue(StringBuilder sb, Object v) { throw new UnsupportedOperationException("paste your existing Json class here"); }
    }
}
