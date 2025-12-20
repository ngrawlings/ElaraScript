package com.elara.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    public ElaraStateStore captureOutputs(Map<String, ElaraScript.Value> outputs) {
        if (outputs == null) return this;
        for (Map.Entry<String, ElaraScript.Value> e : outputs.entrySet()) {
            state.put(e.getKey(), ValueCodec.toPlainJava(e.getValue()));
        }
        return this;
    }

    /**
     * Capture the full debug environment (includes intermediates). Requires includeDebugEnv=true.
     */
    public ElaraStateStore captureEnv(Map<String, ElaraScript.Value> env) {
        if (env == null) return this;
        for (Map.Entry<String, ElaraScript.Value> e : env.entrySet()) {
            state.put(e.getKey(), ValueCodec.toPlainJava(e.getValue()));
        }
        return this;
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

        if (v instanceof ElaraScript.Value) {
            return ValueCodec.toPlainJava((ElaraScript.Value) v);
        }

        if (v instanceof List) {
            List<?> src = (List<?>) v;
            List<Object> out = new ArrayList<>(src.size());
            for (Object item : src) out.add(deepCopyJsonSafe(item));
            return out;
        }

        if (v instanceof Map) {
            // We intentionally only allow string keys.
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
     * Converts ElaraScript.Value into JSON-safe Java types.
     * This is host-side only and keeps the engine clean.
     */
    public static final class ValueCodec {
        private ValueCodec() {}

        public static Object toPlainJava(ElaraScript.Value v) {
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
                case ARRAY: {
                    List<Object> out = new ArrayList<>();
                    for (ElaraScript.Value item : v.asArray()) out.add(toPlainJava(item));
                    return out;
                }
                case MATRIX: {
                    List<Object> rows = new ArrayList<>();
                    for (List<ElaraScript.Value> row : v.asMatrix()) {
                        List<Object> r = new ArrayList<>();
                        for (ElaraScript.Value item : row) r.add(toPlainJava(item));
                        rows.add(r);
                    }
                    return rows;
                }
                case MAP: {
                    Map<String, Object> out = new LinkedHashMap<>();
                    Map<String, ElaraScript.Value> m = v.asMap();
                    if (m != null) {
                        for (Map.Entry<String, ElaraScript.Value> e : m.entrySet()) {
                            out.put(e.getKey(), toPlainJava(e.getValue()));
                        }
                    }
                    return out;
                }
                default:
                    throw new IllegalStateException("Unsupported Value type: " + v.getType());
            }
        }
    }

    // ===================== TINY JSON PARSER/WRITER =====================

    /**
     * Minimal JSON implementation (object/array/string/number/bool/null).
     * - Numbers are parsed as Double.
     * - Objects are LinkedHashMap to preserve insertion order.
     * - This is intentionally strict enough for persistence.
     */
    private static final class Json {
        private Json() {}

        static Object parse(String s) {
            if (s == null) throw new IllegalArgumentException("JSON is null");
            Parser p = new Parser(s);
            Object v = p.parseValue();
            p.skipWs();
            if (!p.eof()) throw p.err("Trailing data");
            return v;
        }

        static void writeValue(StringBuilder sb, Object v) {
            if (v == null) {
                sb.append("null");
                return;
            }
            if (v instanceof Boolean) {
                sb.append(((Boolean) v) ? "true" : "false");
                return;
            }
            if (v instanceof Number) {
                double d = ((Number) v).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    sb.append("null");
                } else {
                    // avoid scientific notation for common cases
                    String str = Double.toString(d);
                    sb.append(str);
                }
                return;
            }
            if (v instanceof String) {
                writeString(sb, (String) v);
                return;
            }
            if (v instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> a = (List<Object>) v;
                sb.append('[');
                for (int i = 0; i < a.size(); i++) {
                    if (i > 0) sb.append(',');
                    writeValue(sb, a.get(i));
                }
                sb.append(']');
                return;
            }
            if (v instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) v;
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    writeString(sb, e.getKey());
                    sb.append(':');
                    writeValue(sb, e.getValue());
                }
                sb.append('}');
                return;
            }

            throw new IllegalArgumentException("Not JSON-serializable: " + v.getClass().getName());
        }

        static void writeString(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            sb.append('"');
        }

        private static final class Parser {
            private final String s;
            private int i;

            Parser(String s) { this.s = s; this.i = 0; }

            boolean eof() { return i >= s.length(); }

            void skipWs() {
                while (!eof()) {
                    char c = s.charAt(i);
                    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                    else break;
                }
            }

            RuntimeException err(String msg) {
                return new IllegalArgumentException(msg + " at index " + i);
            }

            Object parseValue() {
                skipWs();
                if (eof()) throw err("Unexpected end");
                char c = s.charAt(i);
                if (c == '{') return parseObject();
                if (c == '[') return parseArray();
                if (c == '"') return parseString();
                if (c == 't') return parseTrue();
                if (c == 'f') return parseFalse();
                if (c == 'n') return parseNull();
                if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
                throw err("Unexpected char '" + c + "'");
            }

            Object parseObject() {
                expect('{');
                skipWs();
                LinkedHashMap<String, Object> m = new LinkedHashMap<>();
                if (peek('}')) { i++; return m; }

                while (true) {
                    skipWs();
                    String key = parseString();
                    skipWs();
                    expect(':');
                    Object val = parseValue();
                    m.put(key, val);
                    skipWs();
                    if (peek('}')) { i++; break; }
                    expect(',');
                }
                return m;
            }

            Object parseArray() {
                expect('[');
                skipWs();
                List<Object> a = new ArrayList<>();
                if (peek(']')) { i++; return a; }

                while (true) {
                    Object v = parseValue();
                    a.add(v);
                    skipWs();
                    if (peek(']')) { i++; break; }
                    expect(',');
                }
                return a;
            }

            String parseString() {
                expect('"');
                StringBuilder sb = new StringBuilder();
                while (!eof()) {
                    char c = s.charAt(i++);
                    if (c == '"') return sb.toString();
                    if (c == '\\') {
                        if (eof()) throw err("Bad escape");
                        char e = s.charAt(i++);
                        switch (e) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u': {
                                if (i + 4 > s.length()) throw err("Bad unicode escape");
                                String hex = s.substring(i, i + 4);
                                i += 4;
                                try {
                                    sb.append((char) Integer.parseInt(hex, 16));
                                } catch (NumberFormatException nfe) {
                                    throw err("Bad unicode hex");
                                }
                                break;
                            }
                            default:
                                throw err("Bad escape char '" + e + "'");
                        }
                    } else {
                        sb.append(c);
                    }
                }
                throw err("Unterminated string");
            }

            Double parseNumber() {
                int start = i;
                if (peek('-')) i++;
                while (!eof() && Character.isDigit(s.charAt(i))) i++;
                if (!eof() && s.charAt(i) == '.') {
                    i++;
                    while (!eof() && Character.isDigit(s.charAt(i))) i++;
                }
                if (!eof()) {
                    char c = s.charAt(i);
                    if (c == 'e' || c == 'E') {
                        i++;
                        if (!eof() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                        while (!eof() && Character.isDigit(s.charAt(i))) i++;
                    }
                }
                String num = s.substring(start, i);
                try {
                    return Double.parseDouble(num);
                } catch (NumberFormatException nfe) {
                    throw err("Bad number");
                }
            }

            Boolean parseTrue() {
                expect('t');
                expect('r');
                expect('u');
                expect('e');
                return Boolean.TRUE;
            }

            Boolean parseFalse() {
                expect('f');
                expect('a');
                expect('l');
                expect('s');
                expect('e');
                return Boolean.FALSE;
            }

            Object parseNull() {
                expect('n');
                expect('u');
                expect('l');
                expect('l');
                return null;
            }

            boolean peek(char c) {
                return !eof() && s.charAt(i) == c;
            }

            void expect(char c) {
                if (eof() || s.charAt(i) != c) throw err("Expected '" + c + "'");
                i++;
            }
        }
    }
}