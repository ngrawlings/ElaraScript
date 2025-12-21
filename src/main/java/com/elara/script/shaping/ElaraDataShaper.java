package com.elara.script.shaping;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * Elara Data Shaping (first draft).
 *
 * Goals:
 * - Standalone component (no dependency on ElaraScript internals)
 * - Generic over runtime Value type via ValueAdapter<V>
 * - Provides: coercion from raw inputs, validation, and output extraction
 *
 * Notes:
 * - This is intentionally a single file to start. As it grows, split into multiple files.
 * - Structural (exact-key) MAP shaping is planned; v1 supports homogeneous MAP value typing via elementType.
 */
public final class ElaraDataShaper<V> {

    // ===================== Public Types =====================

    /** Generic runtime types (mirrors ElaraScript.Value.Type but decoupled). */
    public enum Type { NUMBER, BOOL, STRING, BYTES, ARRAY, MATRIX, MAP, NULL }

    /**
     * Adapter for whatever runtime Value container you use (e.g., ElaraScript.Value).
     * This allows the shaper to validate/coerce without depending on the interpreter.
     */
    public interface ValueAdapter<V> {
        Type typeOf(V v);

        // Scalars
        double asNumber(V v);
        boolean asBool(V v);
        String asString(V v);
        byte[] asBytes(V v);

        // Collections
        List<V> asArray(V v);
        List<List<V>> asMatrix(V v);
        Map<String, V> asMap(V v);

        // Constructors
        V number(double d);
        V bool(boolean b);
        V string(String s);
        V bytes(byte[] b);
        V array(List<V> a);
        V matrix(List<List<V>> m);
        V map(Map<String, V> m);
        V nil();
    }

    /** Single validation error with a stable field/path and a human-readable message. */
    public static final class ValidationError {
        public final String field;
        public final String message;

        public ValidationError(String field, String message) {
            this.field = field;
            this.message = message;
        }

        @Override public String toString() { return field + ": " + message; }
    }

    /** Result of shaping + running + output validation. */
    public static final class RunResult<V> {
        private final boolean ok;
        private final Map<String, V> outputs;
        private final List<ValidationError> errors;
        private final Map<String, V> debugEnv;

        private RunResult(boolean ok, Map<String, V> outputs, List<ValidationError> errors, Map<String, V> debugEnv) {
            this.ok = ok;
            this.outputs = outputs;
            this.errors = errors;
            this.debugEnv = debugEnv;
        }

        public boolean ok() { return ok; }
        public Map<String, V> outputs() { return outputs; }
        public List<ValidationError> errors() { return errors; }
        public Map<String, V> debugEnv() { return debugEnv; }
    }

    /** Describes one field (input or output) and its constraints. */
    public static final class FieldSpec<V> {
        public enum Kind { INPUT, OUTPUT }

        public final Kind kind;
        public final String name;
        public final Type type;

        public boolean required;
        public V defaultValue;

        // NUMBER
        public Double min;
        public Double max;
        public boolean integerOnly;

        // STRING / BYTES
        public Integer minLen;
        public Integer maxLen;
        public String regex;

        // ARRAY / MAP values (homogeneous)
        public Integer minItems;
        public Integer maxItems;
        public Type elementType;
        public Double elementMin;
        public Double elementMax;

        // MATRIX
        public Integer minRows;
        public Integer maxRows;
        public Integer minCols;
        public Integer maxCols;
        public Integer maxCells;

        // General
        public boolean coerceFromString = true;

        private FieldSpec(Kind kind, String name, Type type) {
            this.kind = kind;
            this.name = name;
            this.type = type;
        }

        public FieldSpec required(boolean required) { this.required = required; return this; }
        public FieldSpec defaultValue(V v) { this.defaultValue = v; return this; }

        public FieldSpec min(double v) { this.min = v; return this; }
        public FieldSpec max(double v) { this.max = v; return this; }
        public FieldSpec integerOnly(boolean v) { this.integerOnly = v; return this; }

        public FieldSpec minLen(int v) { this.minLen = v; return this; }
        public FieldSpec maxLen(int v) { this.maxLen = v; return this; }
        public FieldSpec regex(String v) { this.regex = v; return this; }

        public FieldSpec minItems(int v) { this.minItems = v; return this; }
        public FieldSpec maxItems(int v) { this.maxItems = v; return this; }
        public FieldSpec elementType(Type t) { this.elementType = t; return this; }
        public FieldSpec elementMin(double v) { this.elementMin = v; return this; }
        public FieldSpec elementMax(double v) { this.elementMax = v; return this; }

        public FieldSpec minRows(int v) { this.minRows = v; return this; }
        public FieldSpec maxRows(int v) { this.maxRows = v; return this; }
        public FieldSpec minCols(int v) { this.minCols = v; return this; }
        public FieldSpec maxCols(int v) { this.maxCols = v; return this; }
        public FieldSpec maxCells(int v) { this.maxCells = v; return this; }

        public FieldSpec coerceFromString(boolean v) { this.coerceFromString = v; return this; }
    }

    /**
     * Shape containing input + output field declarations and global limits.
     * Keep this small and deterministic; avoid full JSON Schema complexity.
     */
    public static final class Shape<V> {
        private final LinkedHashMap<String, FieldSpec<V>> inputs = new LinkedHashMap<>();
        private final LinkedHashMap<String, FieldSpec<V>> outputs = new LinkedHashMap<>();

        public int maxStringLength = 8_192;
        public int maxBytesLength = 1_000_000;
        public int maxArrayLength = 10_000;
        public int maxMapItems = 10_000;
        public int maxMatrixCells = 250_000;

        public FieldSpec<V> input(String name, Type type) {
            FieldSpec<V> fs = new FieldSpec<>(FieldSpec.Kind.INPUT, name, type);
            inputs.put(name, fs);
            return fs;
        }

        public FieldSpec<V> output(String name, Type type) {
            FieldSpec<V> fs = new FieldSpec<>(FieldSpec.Kind.OUTPUT, name, type);
            outputs.put(name, fs);
            return fs;
        }

        public Map<String, FieldSpec<V>> inputs() { return Collections.unmodifiableMap(inputs); }
        public Map<String, FieldSpec<V>> outputs() { return Collections.unmodifiableMap(outputs); }
    }

    /** Runner callback: takes initial env and returns final env snapshot. */
    public interface EnvRunner<V> {
        Map<String, V> run(Map<String, V> initialEnv) throws RuntimeException;
    }

    // ===================== Instance =====================

    private final ValueAdapter<V> adapter;

    public ElaraDataShaper(ValueAdapter<V> adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    // ===================== Public API =====================

    /**
     * Validate a single already-coerced runtime value against a field spec.
     *
     * This is useful for interpreter-integrations that already have V values
     * (e.g. validating user-function arguments) and do not want to go through
     * the raw-input coercion path.
     *
     * @return a list of validation errors (empty if OK)
     */
    public List<ValidationError> validate(FieldSpec<V> spec, V value, Shape<V> shape, String path) {
        if (spec == null) throw new IllegalArgumentException("spec must not be null");
        if (shape == null) throw new IllegalArgumentException("shape must not be null");
        String p = (path == null || path.isEmpty()) ? spec.name : path;
        List<ValidationError> errors = new ArrayList<>();
        validateValue(spec, value, shape, errors, p);
        return errors;
    }

    /**
     * Full shaping pipeline:
     *  1) Coerce/validate raw inputs -> initial env
     *  2) Run envRunner to get full env snapshot
     *  3) Validate + extract outputs
     *
     * If includeDebugEnv is true, debugEnv contains the initial env on validation failure,
     * or the full env snapshot on success / runtime execution.
     */
    public RunResult<V> run(
            Shape<V> shape,
            Map<String, Object> rawInputs,
            EnvRunner<V> envRunner,
            boolean includeDebugEnv
    ) {
        if (shape == null) throw new IllegalArgumentException("shape must not be null");
        if (rawInputs == null) rawInputs = Collections.emptyMap();
        if (envRunner == null) throw new IllegalArgumentException("envRunner must not be null");

        List<ValidationError> errors = new ArrayList<>();
        Map<String, V> initialEnv = new LinkedHashMap<>();

        // 1) Inputs: coerce + validate
        for (FieldSpec<V> spec : shape.inputs().values()) {
            Object raw = rawInputs.get(spec.name);

            if (raw == null) {
                if (spec.defaultValue != null) initialEnv.put(spec.name, spec.defaultValue);
                else if (spec.required) errors.add(new ValidationError(spec.name, "Missing required input"));
                continue;
            }

            V coerced;
            try {
                coerced = coerceRawToValue(spec, raw, shape);
            } catch (RuntimeException ex) {
                errors.add(new ValidationError(spec.name, ex.getMessage()));
                continue;
            }

            validateValue(spec, coerced, shape, errors, spec.name);
            if (errors.isEmpty()) initialEnv.put(spec.name, coerced);
        }

        if (!errors.isEmpty()) {
            return new RunResult<>(false, Collections.emptyMap(), errors, includeDebugEnv ? initialEnv : null);
        }

        // 2) Execute
        Map<String, V> fullEnv;
        try {
            fullEnv = envRunner.run(initialEnv);
        } catch (RuntimeException ex) {
            errors.add(new ValidationError("$runtime", ex.getMessage() == null ? "Runtime error" : ex.getMessage()));
            return new RunResult<>(false, Collections.emptyMap(), errors, includeDebugEnv ? initialEnv : null);
        }

        // 3) Outputs: validate + extract
        Map<String, V> out = new LinkedHashMap<>();
        for (FieldSpec<V> spec : shape.outputs().values()) {
            V val = fullEnv.get(spec.name);
            if (val == null) {
                if (spec.required) errors.add(new ValidationError(spec.name, "Missing required output"));
                continue;
            }
            validateValue(spec, val, shape, errors, spec.name);
            if (errors.isEmpty()) out.put(spec.name, val);
        }

        boolean ok = errors.isEmpty();
        return new RunResult<>(ok, ok ? out : Collections.emptyMap(), errors, includeDebugEnv ? fullEnv : null);
    }

    // ===================== Coercion =====================

    private V coerceRawToValue(FieldSpec<V> spec, Object raw, Shape<V> shape) {
        // String coercion for NUMBER/BOOL/STRING
        if (raw instanceof String && spec.coerceFromString) {
            String s = ((String) raw).trim();
            switch (spec.type) {
                case NUMBER:
                    try { return adapter.number(Double.parseDouble(s)); }
                    catch (NumberFormatException nfe) { throw new RuntimeException("Cannot parse number from string"); }
                case BOOL:
                    if ("true".equalsIgnoreCase(s)) return adapter.bool(true);
                    if ("false".equalsIgnoreCase(s)) return adapter.bool(false);
                    throw new RuntimeException("Cannot parse bool from string");
                case STRING:
                    return adapter.string((String) raw);
                default:
                    break;
            }
        }

        switch (spec.type) {
            case NUMBER:
                if (raw instanceof Number) return adapter.number(((Number) raw).doubleValue());
                throw new RuntimeException("Expected number");
            case BOOL:
                if (raw instanceof Boolean) return adapter.bool((Boolean) raw);
                throw new RuntimeException("Expected bool");
            case STRING:
                if (raw instanceof String) {
                    String s = (String) raw;
                    if (s.length() > shape.maxStringLength) throw new RuntimeException("String too long");
                    return adapter.string(s);
                }
                throw new RuntimeException("Expected string");
            case BYTES:
                return coerceBytes(raw, shape);
            case ARRAY:
                if (!(raw instanceof List)) throw new RuntimeException("Expected array/list");
                return adapter.array(coerceList((List<?>) raw, spec, shape));
            case MATRIX:
                if (!(raw instanceof List)) throw new RuntimeException("Expected matrix/list of rows");
                return adapter.matrix(coerceMatrix((List<?>) raw, spec, shape));
            case MAP:
                if (!(raw instanceof Map)) throw new RuntimeException("Expected map/object");
                @SuppressWarnings("unchecked")
                Map<String, Object> rm = (Map<String, Object>) raw;
                if (rm.size() > shape.maxMapItems) throw new RuntimeException("Map too large (max " + shape.maxMapItems + ")");
                Map<String, V> mv = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : rm.entrySet()) {
                    mv.put(e.getKey(), coerceAny(e.getValue(), shape));
                }
                return adapter.map(mv);
            case NULL:
            default:
                return adapter.nil();
        }
    }

    private V coerceBytes(Object raw, Shape<V> shape) {
        if (raw instanceof byte[]) {
            byte[] b = (byte[]) raw;
            if (b.length > shape.maxBytesLength) throw new RuntimeException("Bytes too large (max " + shape.maxBytesLength + ")");
            return adapter.bytes(b);
        }
        if (raw instanceof ByteBuffer) {
            ByteBuffer bb = (ByteBuffer) raw;
            ByteBuffer dup = bb.slice();
            byte[] out = new byte[dup.remaining()];
            dup.get(out);
            if (out.length > shape.maxBytesLength) throw new RuntimeException("Bytes too large (max " + shape.maxBytesLength + ")");
            return adapter.bytes(out);
        }
        if (raw instanceof List) {
            List<?> list = (List<?>) raw;
            if (list.size() > shape.maxBytesLength) throw new RuntimeException("Bytes too large (max " + shape.maxBytesLength + ")");
            byte[] out = new byte[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (!(item instanceof Number)) throw new RuntimeException("Bytes list elements must be numbers 0..255");
                int v = ((Number) item).intValue();
                if (v < 0 || v > 255) throw new RuntimeException("Byte value out of range at index " + i);
                out[i] = (byte) v;
            }
            return adapter.bytes(out);
        }
        throw new RuntimeException("Expected bytes (byte[] / ByteBuffer / List<Number>)");
    }

    private List<V> coerceList(List<?> rawList, FieldSpec<V> spec, Shape<V> shape) {
        if (rawList.size() > shape.maxArrayLength) throw new RuntimeException("Array too large (max " + shape.maxArrayLength + ")");
        List<V> out = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (spec.elementType == null) {
                out.add(coerceAny(item, shape));
            } else {
                FieldSpec<V> tmp = new FieldSpec(FieldSpec.Kind.INPUT, spec.name + "[]", spec.elementType);
                tmp.coerceFromString = spec.coerceFromString;
                out.add(coerceRawToValue(tmp, item, shape));
            }
        }
        return out;
    }

    private List<List<V>> coerceMatrix(List<?> rawRows, FieldSpec<V> spec, Shape<V> shape) {
        List<List<V>> out = new ArrayList<>(rawRows.size());
        int cols = -1;
        int totalCells = 0;

        for (Object rowObj : rawRows) {
            if (!(rowObj instanceof List)) throw new RuntimeException("Matrix rows must be lists");
            List<?> rawRow = (List<?>) rowObj;
            if (cols < 0) cols = rawRow.size();
            if (rawRow.size() != cols) throw new RuntimeException("Matrix must be rectangular");

            totalCells += rawRow.size();
            int limit = (spec.maxCells != null) ? spec.maxCells : shape.maxMatrixCells;
            if (totalCells > limit) throw new RuntimeException("Matrix too large (max cells " + limit + ")");

            Type et = (spec.elementType != null) ? spec.elementType : Type.NUMBER;
            FieldSpec<V> rowSpec = new FieldSpec(FieldSpec.Kind.INPUT, spec.name + "[][]", Type.ARRAY).elementType(et);
            rowSpec.coerceFromString = spec.coerceFromString;

            out.add(coerceList(rawRow, rowSpec, shape));
        }

        return out;
    }

    private V coerceAny(Object raw, Shape<V> shape) {
        if (raw == null) return adapter.nil();
        if (raw instanceof Boolean) return adapter.bool((Boolean) raw);
        if (raw instanceof byte[]) {
            byte[] b = (byte[]) raw;
            if (b.length > shape.maxBytesLength) throw new RuntimeException("Bytes too large");
            return adapter.bytes(b);
        }
        if (raw instanceof Number) return adapter.number(((Number) raw).doubleValue());
        if (raw instanceof String) {
            String s = (String) raw;
            if (s.length() > shape.maxStringLength) throw new RuntimeException("String too long");
            return adapter.string(s);
        }
        if (raw instanceof List) {
            List<?> list = (List<?>) raw;
            if (!list.isEmpty() && list.get(0) instanceof List) {
                FieldSpec<V> ms = new FieldSpec(FieldSpec.Kind.INPUT, "<matrix>", Type.MATRIX);
                return adapter.matrix(coerceMatrix(list, ms, shape));
            }
            FieldSpec<V> as = new FieldSpec(FieldSpec.Kind.INPUT, "<array>", Type.ARRAY);
            return adapter.array(coerceList(list, as, shape));
        }
        if (raw instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) raw;
            if (m.size() > shape.maxMapItems) throw new RuntimeException("Map too large");
            Map<String, V> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                out.put(e.getKey(), coerceAny(e.getValue(), shape));
            }
            return adapter.map(out);
        }

        throw new RuntimeException("Unsupported input type: " + raw.getClass().getName());
    }

    // ===================== Validation =====================

    private void validateValue(FieldSpec<V> spec, V v, Shape<V> shape, List<ValidationError> errors, String path) {
        if (v == null) {
            if (spec.required) errors.add(new ValidationError(path, "Value is required"));
            return;
        }
        Type vt = adapter.typeOf(v);
        if (spec.type != vt) {
            errors.add(new ValidationError(path, "Expected type " + spec.type + ", got " + vt));
            return;
        }

        switch (spec.type) {
            case NUMBER: {
                double d = adapter.asNumber(v);
                if (spec.integerOnly && d != Math.rint(d)) errors.add(new ValidationError(path, "Expected integer"));
                if (spec.min != null && d < spec.min) errors.add(new ValidationError(path, "Must be >= " + spec.min));
                if (spec.max != null && d > spec.max) errors.add(new ValidationError(path, "Must be <= " + spec.max));
                break;
            }
            case STRING: {
                String s = adapter.asString(v);
                if (s == null) s = "";
                if (s.length() > shape.maxStringLength) { errors.add(new ValidationError(path, "String too long")); break; }
                if (spec.minLen != null && s.length() < spec.minLen) errors.add(new ValidationError(path, "Length must be >= " + spec.minLen));
                if (spec.maxLen != null && s.length() > spec.maxLen) errors.add(new ValidationError(path, "Length must be <= " + spec.maxLen));
                if (spec.regex != null && !s.matches(spec.regex)) errors.add(new ValidationError(path, "Does not match required pattern"));
                break;
            }
            case BYTES: {
                byte[] bb = adapter.asBytes(v);
                int n = (bb == null) ? 0 : bb.length;
                if (n > shape.maxBytesLength) { errors.add(new ValidationError(path, "Bytes too large")); break; }
                if (spec.minLen != null && n < spec.minLen) errors.add(new ValidationError(path, "Length must be >= " + spec.minLen));
                if (spec.maxLen != null && n > spec.maxLen) errors.add(new ValidationError(path, "Length must be <= " + spec.maxLen));
                break;
            }
            case BOOL:
                break;
            case ARRAY: {
                List<V> list = adapter.asArray(v);
                if (list == null) list = Collections.emptyList();
                if (list.size() > shape.maxArrayLength) { errors.add(new ValidationError(path, "Array too large")); break; }
                if (spec.minItems != null && list.size() < spec.minItems) errors.add(new ValidationError(path, "Must have at least " + spec.minItems + " items"));
                if (spec.maxItems != null && list.size() > spec.maxItems) errors.add(new ValidationError(path, "Must have at most " + spec.maxItems + " items"));
                if (spec.elementType != null) {
                    for (int i = 0; i < list.size(); i++) {
                        V item = list.get(i);
                        Type it = adapter.typeOf(item);
                        if (it != spec.elementType) {
                            errors.add(new ValidationError(path + "[" + i + "]", "Expected " + spec.elementType + ", got " + it));
                            break;
                        }
                        if (spec.elementType == Type.NUMBER) {
                            double d = adapter.asNumber(item);
                            if (spec.elementMin != null && d < spec.elementMin) { errors.add(new ValidationError(path + "[" + i + "]", "Must be >= " + spec.elementMin)); break; }
                            if (spec.elementMax != null && d > spec.elementMax) { errors.add(new ValidationError(path + "[" + i + "]", "Must be <= " + spec.elementMax)); break; }
                        }
                    }
                }
                break;
            }
            case MATRIX: {
                List<List<V>> rows = adapter.asMatrix(v);
                if (rows == null) rows = Collections.emptyList();
                int r = rows.size();
                if (spec.minRows != null && r < spec.minRows) errors.add(new ValidationError(path, "Rows must be >= " + spec.minRows));
                if (spec.maxRows != null && r > spec.maxRows) errors.add(new ValidationError(path, "Rows must be <= " + spec.maxRows));

                int c = (r == 0) ? 0 : (rows.get(0) == null ? 0 : rows.get(0).size());
                for (int i = 0; i < r; i++) {
                    List<V> row = rows.get(i);
                    int cs = (row == null) ? 0 : row.size();
                    if (cs != c) { errors.add(new ValidationError(path, "Matrix must be rectangular")); return; }
                }

                if (spec.minCols != null && c < spec.minCols) errors.add(new ValidationError(path, "Cols must be >= " + spec.minCols));
                if (spec.maxCols != null && c > spec.maxCols) errors.add(new ValidationError(path, "Cols must be <= " + spec.maxCols));

                int cells = r * c;
                int limit = (spec.maxCells != null) ? spec.maxCells : shape.maxMatrixCells;
                if (cells > limit) errors.add(new ValidationError(path, "Matrix too large"));

                Type et = (spec.elementType != null) ? spec.elementType : Type.NUMBER;
                outer:
                for (int i = 0; i < r; i++) {
                    for (int j = 0; j < c; j++) {
                        V cell = rows.get(i).get(j);
                        if (adapter.typeOf(cell) != et) {
                            errors.add(new ValidationError(path + "[" + i + "][" + j + "]", "Expected " + et + ", got " + adapter.typeOf(cell)));
                            break outer;
                        }
                    }
                }
                break;
            }
            case MAP: {
                Map<String, V> m = adapter.asMap(v);
                int n = (m == null) ? 0 : m.size();
                if (n > shape.maxMapItems) { errors.add(new ValidationError(path, "Map too large")); break; }
                if (spec.minItems != null && n < spec.minItems) errors.add(new ValidationError(path, "Must have at least " + spec.minItems + " entries"));
                if (spec.maxItems != null && n > spec.maxItems) errors.add(new ValidationError(path, "Must have at most " + spec.maxItems + " entries"));

                // Homogeneous value typing (v1)
                if (spec.elementType != null && m != null) {
                    for (Map.Entry<String, V> e : m.entrySet()) {
                        V vv = e.getValue();
                        if (vv == null || adapter.typeOf(vv) != spec.elementType) {
                            errors.add(new ValidationError(path + "." + e.getKey(),
                                    "Expected " + spec.elementType + ", got " + (vv == null ? "null" : adapter.typeOf(vv))));
                            break;
                        }
                    }
                }

                // TODO(vNext): Structural map shapes (required/optional keys + nested validation)
                break;
            }
            case NULL:
            default:
                break;
        }
    }
}