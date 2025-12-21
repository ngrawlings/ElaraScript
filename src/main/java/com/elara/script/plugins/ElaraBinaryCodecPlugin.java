package com.elara.script.plugins;

import com.elara.script.ElaraScript;
import com.elara.script.ElaraScript.Value;
import com.elara.script.shaping.ElaraDataShaper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Binary codec helpers (bytes-focused).
 *
 * Updated for the NEW DataShaping system:
 * - Uses ElaraScript.DataShapingRegistry (engine.dataShaping()) when you want schema validation
 * - Does NOT depend on the removed legacy DataShape/RunResult system
 *
 * Notes:
 * - This implementation keeps the existing public plugin contract: {@code public static void register(ElaraScript engine)}.
 * - The codec here is intentionally minimal and only supports the format used by current tests
 *   ("bytes:N" fields). Extend as needed.
 */
public final class ElaraBinaryCodecPlugin {

    private ElaraBinaryCodecPlugin() {}

    /** Register built-ins on an engine instance. */
    public static void register(ElaraScript engine) {
        Objects.requireNonNull(engine, "engine");

        // Encodes env pairs to a flat BYTES blob using a fmt JSON string.
        engine.registerFunction("bin_encode_bytes", args -> {
            if (args.size() != 2) throw new RuntimeException("bin_encode_bytes(fmt, pairs) expects 2 args");
            Value fmtV = args.get(0);
            Value pairsV = args.get(1);
            if (fmtV.getType() != Value.Type.STRING) throw new RuntimeException("fmt must be STRING");
            if (pairsV.getType() != Value.Type.ARRAY) throw new RuntimeException("pairs must be ARRAY");

            String fmtJson = fmtV.asString();
            List<Field> fields = parseFields(fmtJson);
            Map<String, Value> pairs = pairsArrayToMap(pairsV);

            // Optional: validate against a registry shape whose name matches fmt prefix, if you want.
            // (No-op here; ElaraScript handles user-function arg validation separately.)

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Field f : fields) {
                Value v = pairs.get(f.name);
                if (v == null) throw new RuntimeException("Missing field: " + f.name);
                if (f.kind != Kind.BYTES) throw new RuntimeException("Unsupported field type: " + f.rawType);
                if (v.getType() != Value.Type.BYTES) throw new RuntimeException("Field '" + f.name + "' must be BYTES");
                byte[] b = v.asBytes();
                if (b.length != f.bytesLen) {
                    throw new RuntimeException("Field '" + f.name + "' bytes length mismatch. Expected " + f.bytesLen + " got " + b.length);
                }
                out.writeBytes(b);
            }
            return Value.bytes(out.toByteArray());
        });

        // Decodes a flat BYTES blob to env pairs using a fmt JSON string.
        engine.registerFunction("bin_decode_bytes", args -> {
            if (args.size() != 2) throw new RuntimeException("bin_decode_bytes(fmt, blob) expects 2 args");
            Value fmtV = args.get(0);
            Value blobV = args.get(1);
            if (fmtV.getType() != Value.Type.STRING) throw new RuntimeException("fmt must be STRING");
            if (blobV.getType() != Value.Type.BYTES) throw new RuntimeException("blob must be BYTES");

            String fmtJson = fmtV.asString();
            List<Field> fields = parseFields(fmtJson);
            byte[] blob = blobV.asBytes();

            int expected = 0;
            for (Field f : fields) {
                if (f.kind != Kind.BYTES) throw new RuntimeException("Unsupported field type: " + f.rawType);
                expected += f.bytesLen;
            }
            if (blob.length != expected) {
                throw new RuntimeException("Encoded blob length mismatch. Expected " + expected + " got " + blob.length);
            }

            List<Value> pairs = new ArrayList<>();
            int off = 0;
            for (Field f : fields) {
                byte[] slice = Arrays.copyOfRange(blob, off, off + f.bytesLen);
                off += f.bytesLen;
                // pair = ["name", <BYTES>]
                pairs.add(Value.array(List.of(Value.string(f.name), Value.bytes(slice))));
            }
            return Value.array(pairs);
        });
    }

    // ---------------------------------------------------------------------------------
    // Optional helpers for schema validation (NEW system)
    // ---------------------------------------------------------------------------------

    /**
     * Validate inputs map against a named registry shape.
     * If required=true and shape missing => throws.
     */
    public static void validateInputsOrThrow(ElaraScript engine, String shapeName, Map<String, Value> inputs, boolean required) {
        if (shapeName == null || shapeName.isBlank()) {
            if (required) throw new IllegalArgumentException("shapeName required");
            return;
        }
        var reg = engine.dataShaping();
        if (!reg.has(shapeName)) {
            if (required) throw new IllegalArgumentException("Unknown shape: " + shapeName);
            return;
        }
        ElaraDataShaper.Shape<Value> shape = reg.get(shapeName);
        ElaraDataShaper<Value> shaper = reg.shaper();

        List<ElaraDataShaper.ValidationError> errs = new ArrayList<>();
        for (ElaraDataShaper.FieldSpec<Value> spec : shape.inputs().values()) {
            Value v = inputs.get(spec.name);
            if (v == null) {
                if (spec.required) errs.add(new ElaraDataShaper.ValidationError(spec.name, "required"));
                continue;
            }
            errs.addAll(shaper.validate(spec, v, shape, spec.name));
        }
        if (!errs.isEmpty()) throw new IllegalArgumentException("Shape '" + shapeName + "' input validation failed: " + errs);
    }

    /**
     * Validate outputs map against a named registry shape.
     * If required=true and shape missing => throws.
     */
    public static void validateOutputsOrThrow(ElaraScript engine, String shapeName, Map<String, Value> outputs, boolean required) {
        if (shapeName == null || shapeName.isBlank()) {
            if (required) throw new IllegalArgumentException("shapeName required");
            return;
        }
        var reg = engine.dataShaping();
        if (!reg.has(shapeName)) {
            if (required) throw new IllegalArgumentException("Unknown shape: " + shapeName);
            return;
        }
        ElaraDataShaper.Shape<Value> shape = reg.get(shapeName);
        ElaraDataShaper<Value> shaper = reg.shaper();

        List<ElaraDataShaper.ValidationError> errs = new ArrayList<>();
        for (ElaraDataShaper.FieldSpec<Value> spec : shape.outputs().values()) {
            Value v = outputs.get(spec.name);
            if (v == null) {
                if (spec.required) errs.add(new ElaraDataShaper.ValidationError(spec.name, "required"));
                continue;
            }
            errs.addAll(shaper.validate(spec, v, shape, spec.name));
        }
        if (!errs.isEmpty()) throw new IllegalArgumentException("Shape '" + shapeName + "' output validation failed: " + errs);
    }

    // ---------------------------------------------------------------------------------
    // Internal: fmt parsing
    // ---------------------------------------------------------------------------------

    private enum Kind { BYTES }

    private static final class Field {
        final String name;
        final Kind kind;
        final int bytesLen;
        final String rawType;

        Field(String name, Kind kind, int bytesLen, String rawType) {
            this.name = name;
            this.kind = kind;
            this.bytesLen = bytesLen;
            this.rawType = rawType;
        }
    }

    /**
     * Minimal parser for fmt JSON used by tests.
     * Expected shape: {"fields":[{"name":"blob","type":"bytes:4"}, ...]}
     */
    private static List<Field> parseFields(String fmtJson) {
        if (fmtJson == null) throw new RuntimeException("fmtJson is null");
        String s = fmtJson;

        List<Field> out = new ArrayList<>();

        // Ultra-small "parser": scan for "name" then "type" in each field object.
        int idx = 0;
        while (true) {
            int nameK = s.indexOf("\"name\"", idx);
            if (nameK < 0) break;
            int nameColon = s.indexOf(':', nameK);
            int nameQ1 = s.indexOf('"', nameColon + 1);
            int nameQ2 = s.indexOf('"', nameQ1 + 1);
            if (nameQ1 < 0 || nameQ2 < 0) throw new RuntimeException("Invalid fmtJson: cannot parse name");
            String name = s.substring(nameQ1 + 1, nameQ2);

            int typeK = s.indexOf("\"type\"", nameQ2);
            if (typeK < 0) throw new RuntimeException("Invalid fmtJson: missing type for field " + name);
            int typeColon = s.indexOf(':', typeK);
            int typeQ1 = s.indexOf('"', typeColon + 1);
            int typeQ2 = s.indexOf('"', typeQ1 + 1);
            if (typeQ1 < 0 || typeQ2 < 0) throw new RuntimeException("Invalid fmtJson: cannot parse type for field " + name);
            String type = s.substring(typeQ1 + 1, typeQ2);

            Field f = parseField(name, type);
            out.add(f);

            idx = typeQ2 + 1;
        }

        if (out.isEmpty()) throw new RuntimeException("Invalid fmtJson: no fields parsed");
        return out;
    }

    private static Field parseField(String name, String type) {
        if (type == null) throw new RuntimeException("Invalid field type for " + name);
        String t = type.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("bytes:")) {
            int n;
            try {
                n = Integer.parseInt(t.substring("bytes:".length()).trim());
            } catch (Exception e) {
                throw new RuntimeException("Invalid bytes length in type for field " + name + ": " + type);
            }
            if (n < 0) throw new RuntimeException("Invalid bytes length for field " + name + ": " + n);
            return new Field(name, Kind.BYTES, n, type);
        }
        throw new RuntimeException("Unsupported field type: " + type);
    }

    // ---------------------------------------------------------------------------------
    // Internal: pairs conversion
    // ---------------------------------------------------------------------------------

    /** pairs: [["k", v], ["k2", v2], ...] */
    private static Map<String, Value> pairsArrayToMap(Value pairsV) {
        List<Value> pairs = pairsV.asArray();
        Map<String, Value> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.size(); i++) {
            Value p = pairs.get(i);
            if (p.getType() != Value.Type.ARRAY) throw new RuntimeException("pairs[" + i + "] must be ARRAY");
            List<Value> two = p.asArray();
            if (two.size() != 2) throw new RuntimeException("pairs[" + i + "] must have 2 elements");
            Value kV = two.get(0);
            if (kV.getType() != Value.Type.STRING) throw new RuntimeException("pairs[" + i + "][0] must be STRING");
            String k = kV.asString();
            out.put(k, two.get(1));
        }
        return out;
    }
}
