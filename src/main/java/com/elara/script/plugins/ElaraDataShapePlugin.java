package com.elara.script.plugins;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.elara.script.ElaraScript;
import com.elara.script.ElaraScript.DataShape;
import com.elara.script.ElaraScript.FieldSpec;

import static com.elara.script.ElaraScript.Value;

public final class ElaraDataShapePlugin {

    private ElaraDataShapePlugin() {}

    // Opaque handle -> DataShape under construction / finalized
    private static final Map<String, DataShape> SHAPES = new ConcurrentHashMap<>();

    public static void register(ElaraScript engine) {

        engine.registerFunction("shape_new", args -> {
            String id = UUID.randomUUID().toString();
            SHAPES.put(id, new DataShape());
            return Value.string(id);
        });

        engine.registerFunction("shape_input", args -> {
            // (handle, name, type, required, defaultOrNull)
            String h = args.get(0).asString();
            String name = args.get(1).asString();
            String typeStr = args.get(2).asString();
            boolean required = args.get(3).asBool();
            Value def = args.get(4);

            DataShape s = mustShape(h);

            Value.Type t = parseType(typeStr);

            FieldSpec fs = s.input(name, t).required(required);

            // defaultOrNull: if NULL, do not set default
            if (def != null && def.getType() != Value.Type.NULL) {
                fs.defaultValue(def);
            }

            return Value.nil();
        });

        engine.registerFunction("shape_output", args -> {
            // (handle, name, type, required)
            String h = args.get(0).asString();
            String name = args.get(1).asString();
            String typeStr = args.get(2).asString();
            boolean required = args.size() >= 4 && args.get(3).asBool();

            DataShape s = mustShape(h);
            Value.Type t = parseType(typeStr);

            s.output(name, t).required(required);
            return Value.nil();
        });

        // Optional: set DataShape limits from script
        engine.registerFunction("shape_limits", args -> {
            // (handle, maxStringLen, maxArrayLen, maxMatrixCells)
            String h = args.get(0).asString();
            DataShape s = mustShape(h);

            s.maxStringLength = (int) args.get(1).asNumber();
            s.maxArrayLength = (int) args.get(2).asNumber();
            s.maxMatrixCells = (int) args.get(3).asNumber();

            return Value.nil();
        });

        engine.registerFunction("shape_forget", args -> {
            String h = args.get(0).asString();
            SHAPES.remove(h);
            return Value.nil();
        });

        // Conversation helpers (implemented here since DataShape doesn't expose them)
        engine.registerFunction("shape_missing", args -> {
            // (handle, envPairsArray) -> array of missing required input names
            String h = args.get(0).asString();
            Value envPairs = args.get(1);

            DataShape s = mustShape(h);
            Map<String, Value> env = pairsToEnv(envPairs);

            List<Value> missing = new ArrayList<>();
            for (FieldSpec spec : s.inputs().values()) {
                if (!spec.required) continue;

                Value v = env.get(spec.name);
                boolean hasDefault = (spec.defaultValue != null);

                // Missing if not provided and no default.
                // Treat explicit null as missing too (conversation-friendly).
                if ((v == null || v.getType() == Value.Type.NULL) && !hasDefault) {
                    missing.add(Value.string(spec.name));
                }
            }

            return Value.array(missing);
        });

        engine.registerFunction("shape_validate", args -> {
            // (handle, envPairsArray) -> [[field, message], ...]
            String h = args.get(0).asString();
            Value envPairs = args.get(1);

            DataShape s = mustShape(h);
            Map<String, Value> env = pairsToEnv(envPairs);

            List<Value> errors = new ArrayList<>();

            for (FieldSpec spec : s.inputs().values()) {
                Value v = env.get(spec.name);

                // Handle missing required (if no default)
                if ((v == null || v.getType() == Value.Type.NULL)) {
                    if (spec.required && spec.defaultValue == null) {
                        errors.add(pair(spec.name, "Missing required input"));
                    }
                    continue;
                }

                // Type check
                if (v.getType() != spec.type) {
                    errors.add(pair(spec.name, "Expected type " + spec.type + ", got " + v.getType()));
                    continue;
                }

                // Minimal validation (mirror the engine's checks at a coarse level)
                switch (spec.type) {
                    case NUMBER: {
                        double d = v.asNumber();
                        if (spec.integerOnly && d != Math.rint(d)) errors.add(pair(spec.name, "Expected integer"));
                        if (spec.min != null && d < spec.min) errors.add(pair(spec.name, "Must be >= " + spec.min));
                        if (spec.max != null && d > spec.max) errors.add(pair(spec.name, "Must be <= " + spec.max));
                        break;
                    }
                    case STRING: {
                        String str = v.asString();
                        if (spec.minLen != null && str.length() < spec.minLen) errors.add(pair(spec.name, "Length must be >= " + spec.minLen));
                        if (spec.maxLen != null && str.length() > spec.maxLen) errors.add(pair(spec.name, "Length must be <= " + spec.maxLen));
                        if (spec.regex != null && !str.matches(spec.regex)) errors.add(pair(spec.name, "Does not match required pattern"));
                        break;
                    }
                    case ARRAY: {
                        List<Value> list = v.asArray();
                        if (spec.minItems != null && list.size() < spec.minItems) errors.add(pair(spec.name, "Must have at least " + spec.minItems + " items"));
                        if (spec.maxItems != null && list.size() > spec.maxItems) errors.add(pair(spec.name, "Must have at most " + spec.maxItems + " items"));
                        if (spec.elementType != null) {
                            for (int i = 0; i < list.size(); i++) {
                                if (list.get(i).getType() != spec.elementType) {
                                    errors.add(pair(spec.name, "Element[" + i + "] expected " + spec.elementType + ", got " + list.get(i).getType()));
                                    break;
                                }
                            }
                        }
                        break;
                    }
                    default:
                        break;
                }
            }

            return Value.array(errors);
        });
    }

    // --- helpers ---

    private static DataShape mustShape(String h) {
        DataShape s = SHAPES.get(h);
        if (s == null) throw new RuntimeException("Unknown shape handle: " + h);
        return s;
    }

    private static Value.Type parseType(String s) {
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "NUMBER" -> Value.Type.NUMBER;
            case "BOOL" -> Value.Type.BOOL;
            case "STRING" -> Value.Type.STRING;
            case "ARRAY" -> Value.Type.ARRAY;
            case "MATRIX" -> Value.Type.MATRIX;
            case "NULL" -> Value.Type.NULL;
            default -> throw new RuntimeException("Unknown type: " + s);
        };
    }

    // envPairs: [ ["key", value], ["key2", value2] ... ]
    private static Map<String, Value> pairsToEnv(Value pairs) {
        if (pairs.getType() != Value.Type.ARRAY) {
            throw new RuntimeException("envPairs must be an array of [key, value] pairs");
        }
        Map<String, Value> m = new LinkedHashMap<>();
        for (Value pair : pairs.asArray()) {
            if (pair.getType() != Value.Type.ARRAY) throw new RuntimeException("Each env pair must be an array");
            List<Value> kv = pair.asArray();
            if (kv.size() != 2) throw new RuntimeException("Each env pair must be [key, value]");
            String k = kv.get(0).asString();
            Value v = kv.get(1);
            m.put(k, v);
        }
        return m;
    }

    private static Value pair(String a, String b) {
        return Value.array(List.of(Value.string(a), Value.string(b)));
    }
}
