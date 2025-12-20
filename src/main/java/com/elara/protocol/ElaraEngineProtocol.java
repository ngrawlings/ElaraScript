package com.elara.protocol;

import com.elara.protocol.util.StateDiff;
import com.elara.protocol.util.StateFingerprint;
import com.elara.script.ElaraScript;
import com.elara.script.ElaraStateStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Pure-Java protocol layer (no android.*, no flutter MethodCall/Result).
 *
 * Responsibilities:
 *  - protocol-owned state lifecycle (state lives as long as this protocol instance lives)
 *  - inject __event globals
 *  - include preprocessing (#include "...") using scripts cached from system.ready payload
 *  - route to event_<type>_<target> or fallback event_router
 *  - run ElaraScript and return {patch, commands, fingerprint} sanitized for channel transport
 *
 * Notes:
 *  - State is JSON-safe: Map<String,Object> where Object is null/bool/num/string/List/Map
 *  - Keys starting with "__" are ignored for diffing (StateDiff) but may exist in stateRaw; callers should not rely on them.
 */
public final class ElaraEngineProtocol {

    /** Host must register native builtins into the engine (Android implementation can use Context). */
    public interface BuiltinsRegistrar {
        void register(ElaraScript engine);
    }

    /** Optional logger (bridge to Logcat in Android). */
    public interface Logger {
        void i(String tag, String msg);
        void w(String tag, String msg);
    }

    private final BuiltinsRegistrar builtins;
    private final Logger log;

    // scripts cached from system.ready payload
    private final Map<String, String> scriptCache = new ConcurrentHashMap<>();

    // -------------------------- Protocol-owned state --------------------------
    // The state lifecycle == this protocol instance lifecycle.
    // If your RPC server creates a new protocol instance per request, state will reset.
    private final Map<String, Object> stateRaw = new LinkedHashMap<>();
    private String stateFingerprint = StateFingerprint.fingerprintRawState(stateRaw);

    private static final Pattern INCLUDE =
            Pattern.compile("^\\s*#include\\s+\"([^\"]+)\"\\s*$");

    public ElaraEngineProtocol(BuiltinsRegistrar builtins, Logger log) {
        if (builtins == null) throw new IllegalArgumentException("builtins is null");
        this.builtins = builtins;
        this.log = log;
    }

    // -------------------------- State lifecycle API --------------------------

    /** Clear all retained state for this protocol instance. */
    public synchronized void resetState() {
        stateRaw.clear();
        stateFingerprint = StateFingerprint.fingerprintRawState(stateRaw);
    }

    /** Replace retained state from JSON (useful when loading an app/session). */
    public synchronized void loadStateJson(String stateJson) {
        if (stateJson == null || stateJson.trim().isEmpty()) {
            resetState();
            return;
        }
        ElaraStateStore s = ElaraStateStore.fromJson(stateJson);
        stateRaw.clear();
        stateRaw.putAll(s.toRawInputs());
        stateFingerprint = StateFingerprint.fingerprintRawState(stateRaw);
    }

    /** Returns the last known fingerprint of retained state. */
    public synchronized String getStateFingerprint() {
        return stateFingerprint;
    }

    /** Returns a defensive copy of the retained raw state. */
    public synchronized Map<String, Object> snapshotStateRaw() {
        return new LinkedHashMap<>(stateRaw);
    }

    // -------------------------- Public API --------------------------

    /**
     * Dispatch an event into the script engine.
     *
     * IMPORTANT:
     * - This method uses and updates protocol-owned state (stateRaw).
     * - It does NOT accept caller-provided stateJson/patch; state comes from this instance.
     *
     * Returns a channel-safe map:
     *   {
     *     "patch": { "set": [[k,v],...], "remove": [k,...] },
     *     "commands": [...],
     *     "fingerprint": "<md5>"
     *   }
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> dispatchEvent(
            String appScript,
            Map<String, Object> event
    ) {
        if (appScript == null || appScript.trim().isEmpty()) {
            throw new IllegalArgumentException("appScript required");
        }
        if (event == null) {
            throw new IllegalArgumentException("event required");
        }

        // --- Baseline is protocol-owned state (snapshot for diff) ---
        Map<String, Object> rawInputs = new LinkedHashMap<>(stateRaw);

        // Build env for script from baseline
        Map<String, ElaraScript.Value> initialEnv = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : rawInputs.entrySet()) {
            initialEnv.put(e.getKey(), coerceAnyToValue(e.getValue()));
        }

        // Inject latest event into env
        Object t = event.get("type");
        Object target = event.get("target");
        Object value = event.get("value");

        initialEnv.put("__event_type", ElaraScript.Value.string(t == null ? "" : String.valueOf(t)));
        initialEnv.put("__event_target", ElaraScript.Value.string(target == null ? "" : String.valueOf(target)));
        initialEnv.put("__event_value", coerceAnyToValue(value));

        List<ElaraScript.Value> tuple = new ArrayList<>();
        tuple.add(initialEnv.get("__event_type"));
        tuple.add(initialEnv.get("__event_target"));
        tuple.add(initialEnv.get("__event_value"));
        initialEnv.put("__event", ElaraScript.Value.array(tuple));

        // Run engine
        ElaraScript engine = new ElaraScript();
        builtins.register(engine);

        // We pass __event as the single argument for convenience
        ElaraScript.Value ev = initialEnv.get("__event");
        if (ev == null || ev.getType() != ElaraScript.Value.Type.ARRAY) {
            throw new RuntimeException("__event missing or not ARRAY");
        }
        List<ElaraScript.Value> evA = ev.asArray();
        if (evA.size() != 3) {
            throw new RuntimeException("__event must be [type, target, payload]");
        }

        List<ElaraScript.Value> args = List.of(evA.get(0), evA.get(1), evA.get(2));

        ElaraScript.Value typeV   = evA.get(0);
        ElaraScript.Value targetV = evA.get(1);
        ElaraScript.Value payload = evA.get(2);

        // cache includes on system.ready (payload may be MAP or legacy PAIRS array)
        if (typeV.getType() == ElaraScript.Value.Type.STRING
                && targetV.getType() == ElaraScript.Value.Type.STRING
                && "system".equals(typeV.asString())
                && "ready".equals(targetV.asString())) {
            if (log != null) log.i("ElaraProtocol", "cacheScriptsFromPayload(system.ready)");
            cacheScriptsFromPayload(payload);
        }

        // preprocess includes (after caching)
        String processed = preprocessIncludes(appScript);

        // Guard: engine must never see a raw include directive
        if (processed.contains("#include")) {
            throw new RuntimeException("Preprocess failed: #include still present");
        }

        // Routing
        String candidate = "event_" + sanitizeIdent(typeV.asString()) + "_" + sanitizeIdent(targetV.asString());
        String fallback = "event_router";
        String entry = engine.hasUserFunction(processed, candidate) ? candidate : fallback;

        ElaraScript.EntryRunResult rr = engine.runWithEntryResult(processed, entry, args, initialEnv);

        ElaraStateStore outStore = new ElaraStateStore().captureEnv(rr.env());

        // Extract JSON-safe view of captured env
        Map<String, Object> raw = outStore.toRawInputs();

        // diff/fingerprint
        Map<String, Object> diff = StateDiff.diff(rawInputs, raw).toPatchObject();
        String fp = StateFingerprint.fingerprintRawState(raw);

        // Update protocol-owned state to match the engine output (state lifecycle == protocol lifecycle)
        stateRaw.clear();
        stateRaw.putAll(raw);
        stateFingerprint = fp;

        Object cmdsObj = raw.get("__commands");
        List<Object> commandsList = (cmdsObj instanceof List) ? (List<Object>) cmdsObj : new ArrayList<>();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("patch", diff);
        resp.put("commands", commandsList);
        resp.put("fingerprint", fp);

        // IMPORTANT: sanitize before crossing platform channel
        return (Map<String, Object>) sanitizeForChannel(resp);
    }

    // -------------------------- Includes cache --------------------------

    private static ElaraScript.Value pairsGet(ElaraScript.Value pairs, String key) {
        if (pairs == null || pairs.getType() != ElaraScript.Value.Type.ARRAY) return null;
        for (ElaraScript.Value kv : pairs.asArray()) {
            var p = kv.asArray();
            if (p.size() == 2 && key.equals(p.get(0).asString())) return p.get(1);
        }
        return null;
    }

    private void cacheScriptsFromPayload(ElaraScript.Value payload) {
        if (payload == null) return;

        ElaraScript.Value scriptsV = null;

        if (payload.getType() == ElaraScript.Value.Type.MAP) {
            Map<String, ElaraScript.Value> m = payload.asMap();
            scriptsV = (m == null) ? null : m.get("scripts");
        } else if (payload.getType() == ElaraScript.Value.Type.ARRAY) {
            scriptsV = pairsGet(payload, "scripts"); // legacy
        }

        if (scriptsV == null || scriptsV.getType() != ElaraScript.Value.Type.ARRAY) {
            if (log != null) {
                log.w("ElaraProtocol", "No scripts to load from payload");
            }
            return;
        }

        for (ElaraScript.Value kv : scriptsV.asArray()) {
            var p = kv.asArray();
            String path = p.get(0).asString();
            String src  = p.get(1).asString();
            scriptCache.put(path, src);
        }
    }

    private String preprocessIncludes(String src) {
        return preprocessIncludes(src, new ArrayDeque<>());
    }

    private String preprocessIncludes(String src, Deque<String> stack) {
        StringBuilder out = new StringBuilder(src.length() + 256);
        String[] lines = src.split("\n", -1);

        for (String line : lines) {
            var m = INCLUDE.matcher(line);
            if (!m.matches()) {
                out.append(line).append('\n');
                continue;
            }

            String path = m.group(1);

            if (stack.contains(path)) {
                throw new RuntimeException("Include cycle: " + stack + " -> " + path);
            }

            String included = scriptCache.get(path);
            if (included == null) {
                throw new RuntimeException("Include not found in script cache: " + path);
            }

            stack.addLast(path);
            out.append(preprocessIncludes(included, stack));
            stack.removeLast();
        }

        return out.toString();
    }

    // -------------------------- Helpers --------------------------

    private static String sanitizeIdent(String s) {
        if (s == null) return "";
        String out = s.replaceAll("[^A-Za-z0-9_]", "_");
        if (!out.isEmpty() && Character.isDigit(out.charAt(0))) out = "_" + out;
        return out;
    }

    private static Object sanitizeForChannel(Object x) {
        if (x == null) return null;

        if (x instanceof Boolean || x instanceof Integer || x instanceof Long ||
                x instanceof Double || x instanceof Float || x instanceof String) {
            return x;
        }

        // IMPORTANT: do NOT return byte[] or ByteBuffer
        if (x instanceof byte[]) {
            byte[] bb = (byte[]) x;
            ArrayList<Integer> out = new ArrayList<>(bb.length);
            for (byte b : bb) out.add(((int) b) & 0xFF);
            return out;
        }

        if (x instanceof java.nio.ByteBuffer) {
            java.nio.ByteBuffer buf = (java.nio.ByteBuffer) x;
            byte[] bb = new byte[buf.remaining()];
            buf.slice().get(bb);
            return sanitizeForChannel(bb);
        }

        if (x instanceof List) {
            List<?> src = (List<?>) x;
            ArrayList<Object> out = new ArrayList<>(src.size());
            for (Object it : src) out.add(sanitizeForChannel(it));
            return out;
        }

        if (x instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) x;
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) {
                Object k = e.getKey();
                if (!(k instanceof String)) {
                    throw new RuntimeException("Channel Map key must be String, got: " + (k == null ? "null" : k.getClass()));
                }
                out.put((String) k, sanitizeForChannel(e.getValue()));
            }
            return out;
        }

        throw new RuntimeException("Unsupported channel type: " + x.getClass().getName());
    }

    /**
     * Supported: null, boolean, number, string, List (array), numeric List<List> (matrix), Map (map)
     */
    @SuppressWarnings("unchecked")
    private static ElaraScript.Value coerceAnyToValue(Object v) {
        if (v == null) return ElaraScript.Value.nil();
        if (v instanceof Boolean) return ElaraScript.Value.bool((Boolean) v);
        if (v instanceof Number) return ElaraScript.Value.number(((Number) v).doubleValue());
        if (v instanceof String) return ElaraScript.Value.string((String) v);

        if (v instanceof List) {
            List<?> src = (List<?>) v;

            // matrix only if truly numeric/bool
            if (!src.isEmpty() && src.get(0) instanceof List) {
                boolean allRows = true;
                boolean numericMatrix = true;

                for (Object row : src) {
                    if (!(row instanceof List)) { allRows = false; break; }
                    for (Object item : (List<?>) row) {
                        if (item == null) { numericMatrix = false; break; }
                        if (!(item instanceof Number) && !(item instanceof Boolean)) {
                            numericMatrix = false;
                            break;
                        }
                    }
                    if (!numericMatrix) break;
                }

                if (allRows && numericMatrix) {
                    List<List<ElaraScript.Value>> rows = new ArrayList<>();
                    for (Object row : src) {
                        List<?> r = (List<?>) row;
                        List<ElaraScript.Value> rv = new ArrayList<>(r.size());
                        for (Object item : r) rv.add(coerceAnyToValue(item));
                        rows.add(rv);
                    }
                    return ElaraScript.Value.matrix(rows);
                }
            }

            // array
            List<ElaraScript.Value> out = new ArrayList<>(src.size());
            for (Object item : src) out.add(coerceAnyToValue(item));
            return ElaraScript.Value.array(out);
        }

        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            java.util.HashMap<String, ElaraScript.Value> out = new java.util.HashMap<>();
            for (var e : m.entrySet()) {
                Object k = e.getKey();
                if (!(k instanceof String)) throw new RuntimeException("Map keys must be strings");
                out.put((String) k, coerceAnyToValue(e.getValue()));
            }
            return ElaraScript.Value.map(out);
        }

        throw new RuntimeException("Unsupported value type: " + v.getClass().getName());
    }
}