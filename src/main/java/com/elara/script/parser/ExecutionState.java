package com.elara.script.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.elara.script.parser.Value.ClassInstance;

/**
 * ExecutionState
 *
 * Canonical, fully-contained runtime state for ElaraScript.
 *
 * Contains:
 *  - liveInstances: all live class instances (key -> ClassInstance)
 *  - env:          environment stack (we persist ONLY root env vars + global vars)
 *
 * Import/Export:
 *  - exportValueState():   Value-native structure (engine-friendly)
 *  - importValueState(...):restore from Value-native state
 *  - exportJsonSafe():     JSON-safe structure (host transport)
 *  - importJsonSafe(...):  restore from JSON-safe transport
 *
 * Notes:
 *  - We intentionally do NOT persist transient call-stack frames.
 *    Only:
 *      - Environment.global (global vars)
 *      - Root env vars (inner-most frame vars)
 */
public class ExecutionState {

    // Namespaced keys to avoid collisions with user program vars.
    public static final String KEY_EXEC = "__elara_exec";
    public static final String KEY_VERSION = "v";
    public static final int VERSION = 1;

    public static final String KEY_ENV = "env";             // map<string, value>
    public static final String KEY_GLOBAL = "global";       // map<string, value>
    public static final String KEY_INSTANCES = "instances"; // array< {key,className,uuid,state} >

    public LinkedHashMap<String, Value.ClassInstance> liveInstances;
    public Map<String, Value> global;
    public Environment env;

    public ExecutionState() {
        this.liveInstances = new LinkedHashMap<>();
        this.global = new LinkedHashMap<>(); 
        this.env = new Environment(this);
    }

    public ExecutionState(Map<String, Value.ClassInstance> instances, Map<String, Value> env_state) {
        this.liveInstances = (instances == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(instances);
        this.global = new LinkedHashMap<>();
        this.env = new Environment(this, env_state == null ? new LinkedHashMap<>() : env_state);
    }

    // ===================== EXPORT (VALUE) =====================

    /**
     * Export a compact, canonical Value-native state map.
     *
     * Shape:
     * {
     *   "__elara_exec": {
     *     "v": 1,
     *     "env": { ... },
     *     "global": { ... },
     *     "instances": [
     *        { "key": "...", "className": "...", "uuid": "...", "state": { ... } }
     *     ]
     *   }
     * }
     */
    public Map<String, Value> exportValueState() {
        LinkedHashMap<String, Value> root = new LinkedHashMap<>();
        root.put(KEY_EXEC, Value.map(exportExecBlockValue()));
        return root;
    }

    private Map<String, Value> exportExecBlockValue() {
        LinkedHashMap<String, Value> exec = new LinkedHashMap<>();
        exec.put(KEY_VERSION, Value.number(VERSION));

        // env/global: persisted as maps of Values
        LinkedHashMap<String, Value> envVars = new LinkedHashMap<>();
        LinkedHashMap<String, Value> globalVars = new LinkedHashMap<>();
        readPersistableEnvAndGlobal(envVars, globalVars);

        exec.put(KEY_ENV, Value.map(envVars));
        exec.put(KEY_GLOBAL, Value.map(globalVars));

        // instances: array of instance records
        List<Value> instArr = new ArrayList<>(liveInstances.size());
        for (Map.Entry<String, ClassInstance> e : liveInstances.entrySet()) {
            String key = e.getKey();
            ClassInstance inst = e.getValue();
            if (inst == null) continue;

            LinkedHashMap<String, Value> rec = new LinkedHashMap<>();
            rec.put("key", Value.string(key));
            rec.put("className", Value.string(inst.className));
            rec.put("uuid", Value.string(inst.uuid));
            rec.put("state", Value.map(inst._this)); // authoritative instance state
            instArr.add(Value.map(rec));
        }
        exec.put(KEY_INSTANCES, Value.array(instArr));

        return exec;
    }

    private void readPersistableEnvAndGlobal(
            LinkedHashMap<String, Value> outEnvVars,
            LinkedHashMap<String, Value> outGlobalVars
    ) {
        // Prefer env snapshot frames to remain aligned with the current runtime model.
        // frames[0] is synthetic global frame (as per your existing snapshot code).
        // inner-most frame is last.
        if (env != null) {
            List<Map<String, Value>> frames = env.snapshotFrames();
            if (frames != null && !frames.isEmpty()) {
                // global
                Map<String, Value> gFrame = frames.get(0);
                Value gVarsV = (gFrame == null) ? null : gFrame.get("vars");
                if (gVarsV != null && gVarsV.getType() == Value.Type.MAP && gVarsV.asMap() != null) {
                    outGlobalVars.putAll(gVarsV.asMap());
                }

                // root env vars (inner-most)
                Map<String, Value> inner = frames.get(frames.size() - 1);
                Value varsV = (inner == null) ? null : inner.get("vars");
                if (varsV != null && varsV.getType() == Value.Type.MAP && varsV.asMap() != null) {
                    outEnvVars.putAll(varsV.asMap());
                }
                return;
            }
        }

        // Fallback: if no frames, still export globals from Environment.global
        if (global != null) {
            outGlobalVars.putAll(global);
        }
    }

    // ===================== IMPORT (VALUE) =====================

    /**
     * Restore ExecutionState from Value-native state exported by exportValueState().
     * Missing or malformed blocks will result in a blank state (but never crash).
     */
    public static ExecutionState importValueState(Map<String, Value> state) {
        ExecutionState out = new ExecutionState();
        if (state == null) return out;

        Value execV = state.get(KEY_EXEC);
        if (execV == null || execV.getType() != Value.Type.MAP || execV.asMap() == null) return out;

        Map<String, Value> exec = execV.asMap();

        // version (optional)
        int v = 1;
        Value vV = exec.get(KEY_VERSION);
        if (vV != null && vV.getType() == Value.Type.NUMBER) {
            double dv = vV.asNumber();
            if (!Double.isNaN(dv) && !Double.isInfinite(dv)) v = (int) dv;
        }
        // If you ever change schema, branch here on v.

        // global vars
        Value globalV = exec.get(KEY_GLOBAL);
        if (globalV != null && globalV.getType() == Value.Type.MAP && globalV.asMap() != null) {
        	out.global.clear();
        	out.global.putAll(globalV.asMap());
        }

        // env vars (root env state)
        LinkedHashMap<String, Value> envVars = new LinkedHashMap<>();
        Value envV = exec.get(KEY_ENV);
        if (envV != null && envV.getType() == Value.Type.MAP && envV.asMap() != null) {
            envVars.putAll(envV.asMap());
        }
        out.env = new Environment(out, envVars);

        // instances
        out.liveInstances.clear();
        Value instV = exec.get(KEY_INSTANCES);
        if (instV != null && instV.getType() == Value.Type.ARRAY && instV.asArray() != null) {
            for (Value recV : instV.asArray()) {
                if (recV == null || recV.getType() != Value.Type.MAP || recV.asMap() == null) continue;
                Map<String, Value> rec = recV.asMap();

                String key = asString(rec.get("key"));
                String className = asString(rec.get("className"));
                String uuid = asString(rec.get("uuid"));
                Value stateV = rec.get("state");

                if (key == null || className == null || uuid == null) continue;

                ClassInstance inst = new ClassInstance(className, uuid);
                if (stateV != null && stateV.getType() == Value.Type.MAP && stateV.asMap() != null) {
                    inst._this.putAll(stateV.asMap());
                }
                out.liveInstances.put(key, inst);
            }
        }

        return out;
    }

    private static String asString(Value v) {
        if (v == null) return null;
        if (v.getType() == Value.Type.STRING) return v.asString();
        // Keep it strict. If you want coercion, add it here.
        return null;
    }

    // ===================== EXPORT/IMPORT (JSON-SAFE) =====================

    /**
     * Export JSON-safe transport object:
     *   Map<String, Object> with only null/bool/number/string/list/map.
     *
     * The returned structure is safe to serialize via any JSON library,
     * or your existing tiny JSON writer.
     */
    public Map<String, Object> exportJsonSafe() {
        Map<String, Value> valueState = exportValueState();
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Value> e : valueState.entrySet()) {
            out.put(e.getKey(), ValueCodec.toPlainJava(e.getValue()));
        }
        return out;
    }

    /**
     * Import from JSON-safe transport object previously produced by exportJsonSafe().
     * Robust to partial/malformed input.
     */
    @SuppressWarnings("unchecked")
    public static ExecutionState importJsonSafe(Map<String, Object> jsonSafe) {
        if (jsonSafe == null) return new ExecutionState();

        LinkedHashMap<String, Value> valueState = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : jsonSafe.entrySet()) {
            valueState.put(e.getKey(), ValueCodec.fromPlainJava(e.getValue()));
        }
        return importValueState(valueState);
    }

    // ===================== VALUE CODEC (LOCAL, NO ENGINE DEPENDENCY) =====================

    /**
     * Converts Value <-> JSON-safe Java types.
     * Kept here so ExecutionState can own import/export without pushing it into the engine.
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
                    // Represent functions as their name / string form for transport.
                    // (Matches your existing host codecs.)
                    return v.asString();
                case ARRAY: {
                    List<Object> out = new ArrayList<>();
                    List<Value> a = v.asArray();
                    if (a != null) for (Value item : a) out.add(toPlainJava(item));
                    return out;
                }
                case MATRIX: {
                    List<Object> rows = new ArrayList<>();
                    List<List<Value>> m = v.asMatrix();
                    if (m != null) {
                        for (List<Value> row : m) {
                            List<Object> r = new ArrayList<>();
                            if (row != null) for (Value item : row) r.add(toPlainJava(item));
                            rows.add(r);
                        }
                    }
                    return rows;
                }
                case MAP: {
                    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
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

        public static Value fromPlainJava(Object o) {
            if (o == null) return Value.nil();
            if (o instanceof Value) return (Value) o;
            if (o instanceof Boolean) return Value.bool((Boolean) o);
            if (o instanceof Number) return Value.number(((Number) o).doubleValue());
            if (o instanceof String) return Value.string((String) o);

            if (o instanceof List) {
                List<?> src = (List<?>) o;

                // Detect matrix: list of lists
                boolean allLists = !src.isEmpty();
                for (Object it : src) {
                    if (!(it instanceof List)) { allLists = false; break; }
                }
                if (allLists) {
                    List<List<Value>> rows = new ArrayList<>(src.size());
                    for (Object rowObj : src) {
                        List<?> rowSrc = (List<?>) rowObj;
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
                Map<?, ?> src = (Map<?, ?>) o;
                LinkedHashMap<String, Value> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : src.entrySet()) {
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
    
    public Map<String, Value> snapshotValue() {
        Map<String, Value> out = new LinkedHashMap<>();

        // environments
        List<Map<String, Value>> frames = (env == null) ? new ArrayList<>() : env.snapshotFrames();
        List<Value> frameVals = new ArrayList<>(frames.size());
        for (Map<String, Value> f : frames) frameVals.add(Value.map(f));
        out.put("environments", Value.array(frameVals));

        // class_instances (match Interpreter.snapshot() shape)
        List<Value> instVals = new ArrayList<>(liveInstances.size());
        for (Map.Entry<String, Value.ClassInstance> e : liveInstances.entrySet()) {
            String key = e.getKey();
            Value.ClassInstance inst = e.getValue();
            if (inst == null) continue;

            Map<String, Value> ci = new LinkedHashMap<>();
            ci.put("key", Value.string(key));
            ci.put("className", Value.string(inst.className));
            ci.put("uuid", Value.string(inst.uuid));
            ci.put("state", Value.map(inst._this));
            instVals.add(Value.map(ci));
        }
        out.put("class_instances", Value.array(instVals));

        return out;
    }

}
