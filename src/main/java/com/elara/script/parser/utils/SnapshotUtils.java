package com.elara.script.parser.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.elara.script.parser.Environment;
import com.elara.script.parser.Value;

/**
 * Snapshot utilities for turning Environment/Value graphs into safe copies
 * suitable for:
 *  - master snapshots shared across workers
 *  - worker published mirrors readable by master
 *
 * IMPORTANT:
 *  - These helpers perform deep copies for MAP/ARRAY/MATRIX/BYTES.
 *  - CLASS descriptors are treated as immutable and shared.
 *  - CLASS_INSTANCE is cloned to avoid sharing the mutable _this map across threads.
 */
public final class SnapshotUtils {

    private SnapshotUtils() {}

    /**
     * Extract the "root vars" of an Environment.
     *
     * Environment.snapshotFrames() returns:
     *  - [0] synthetic global frame
     *  - [1..n] outer -> inner env frames
     *
     * We want the inner-most frame's "vars" map.
     */
    public static Map<String, Value> snapshotRootVars(Environment env) {
        if (env == null) return new LinkedHashMap<>();

        List<Map<String, Value>> frames = env.snapshotFrames();
        if (frames == null || frames.isEmpty()) return new LinkedHashMap<>();

        Map<String, Value> inner = frames.get(frames.size() - 1);
        if (inner == null) return new LinkedHashMap<>();

        Value varsV = inner.get("vars");
        if (varsV == null || varsV.getType() != Value.Type.MAP || varsV.asMap() == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(varsV.asMap());
    }

    /** Deep copy a map of Values. */
    public static Map<String, Value> deepCopyValueMap(Map<String, Value> src) {
        Map<String, Value> out = new LinkedHashMap<>();
        if (src == null) return out;
        for (Map.Entry<String, Value> e : src.entrySet()) {
            out.put(e.getKey(), deepCopyValue(e.getValue()));
        }
        return out;
    }

    /** Deep copy a Value graph. */
    public static Value deepCopyValue(Value v) {
        if (v == null) return null;

        switch (v.getType()) {
            case NULL:
                return Value.nil();

            case NUMBER:
                return Value.number(v.asNumber());

            case BOOL:
                return Value.bool(v.asBool());

            case STRING:
                // Strings are immutable, but keep a new wrapper.
                return Value.string(v.asString());

            case FUNC:
                return Value.func(v.asFunc());

            case BYTES: {
                byte[] b = v.asBytes();
                if (b == null) return Value.bytes(null);
                byte[] copy = java.util.Arrays.copyOf(b, b.length);
                return Value.bytes(copy);
            }

            case ARRAY: {
                List<Value> a = v.asArray();
                if (a == null) return Value.array(null);
                List<Value> out = new ArrayList<>(a.size());
                for (Value item : a) out.add(deepCopyValue(item));
                return Value.array(out);
            }

            case MATRIX: {
                List<List<Value>> m = v.asMatrix();
                if (m == null) return Value.matrix(null);
                List<List<Value>> out = new ArrayList<>(m.size());
                for (List<Value> row : m) {
                    if (row == null) { out.add(null); continue; }
                    List<Value> ro = new ArrayList<>(row.size());
                    for (Value cell : row) ro.add(deepCopyValue(cell));
                    out.add(ro);
                }
                return Value.matrix(out);
            }

            case MAP: {
                Map<String, Value> mm = v.asMap();
                if (mm == null) return Value.map(null);
                Map<String, Value> out = new LinkedHashMap<>();
                for (Map.Entry<String, Value> e : mm.entrySet()) {
                    out.put(e.getKey(), deepCopyValue(e.getValue()));
                }
                return Value.map(out);
            }

            case CLASS:
                // Treat class descriptors as immutable.
                return Value.clazz(v.asClass());

            case CLASS_INSTANCE: {
                Value.ClassInstance inst = v.asClassInstance();
                // Clone instance identity, but deep copy its _this map so snapshot reads are race-free.
                Value.ClassInstance clone = new Value.ClassInstance(inst.className, inst.uuid);
                for (Map.Entry<String, Value> e : inst._this.entrySet()) {
                    clone._this.put(e.getKey(), deepCopyValue(e.getValue()));
                }
                return new Value(Value.Type.CLASS_INSTANCE, clone);
            }

            default:
                throw new RuntimeException("SnapshotUtils.deepCopyValue: unsupported type: " + v.getType());
        }
    }
    
    /** Merge vars from snapshot["environments"] outer → inner (inner overrides). */
    public static Map<String, Value> mergedVars(Map<String, Value> snapshot) {
        LinkedHashMap<String, Value> merged = new LinkedHashMap<>();
        if (snapshot == null) return merged;

        Value envsV = snapshot.get("environments");
        if (envsV == null || envsV.getType() != Value.Type.ARRAY || envsV.asArray() == null) {
            return merged;
        }

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
}